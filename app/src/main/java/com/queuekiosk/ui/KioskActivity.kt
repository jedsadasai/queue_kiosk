package com.queuekiosk.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.queuekiosk.R
import com.queuekiosk.data.WorkingHours
import com.queuekiosk.databinding.ActivityKioskBinding
import com.queuekiosk.databinding.DialogTicketBinding
import com.queuekiosk.kiosk
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** fleQ MainActivity: the patient-facing "take a queue ticket" screen. */
class KioskActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKioskBinding
    private val viewModel: KioskViewModel by viewModels()

    private var ticketDialog: AlertDialog? = null
    private var countdownJob: Job? = null
    private val dateFormat = SimpleDateFormat("EEEE d MMMM yyyy  HH:mm", Locale.forLanguageTag("th-TH"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityKioskBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Bars are hidden (immersive), but the cutout and transient bars still produce insets.
        binding.root.applySystemBarsPadding()

        // Kiosk: Back does nothing.
        onBackPressedDispatcher.addCallback(this) { }

        val options = kiosk.ticketOptions()
        binding.kioskOrgName.text = options.orgName
        binding.kioskWorkflow.text = kiosk.prefs.workflowName
        binding.kioskWorkflow.isVisible = kiosk.prefs.workflowName.isNotBlank()

        binding.kioskTakeQueue.setOnClickListener { viewModel.takeQueue() }
        // Hidden staff menu: long-press the header.
        binding.kioskHeader.setOnLongClickListener { showAdminMenu(); true }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.events.collect { handle(it) } }
                launch {
                    while (true) {
                        updateClockAndScreen()
                        delay(CLOCK_TICK_MS)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!kiosk.prefs.hasPrinter || !KioskPermissions.granted(this)) {
            goToPrinterSetup(autoReconnect = false)
            return
        }
        viewModel.connectPrinter()
    }

    override fun onStop() {
        super.onStop()
        viewModel.disconnectPrinter()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        ticketDialog?.dismiss()
        super.onDestroy()
    }

    private fun render(s: KioskViewModel.UiState) {
        binding.kioskTakeQueue.isEnabled = !s.busy
        binding.kioskProgress.isVisible = s.busy
        val (textRes, colorRes) = when (s.printer) {
            KioskViewModel.PrinterStatus.CONNECTED -> R.string.kiosk_printer_status_connected to R.color.ok
            KioskViewModel.PrinterStatus.CONNECTING -> R.string.kiosk_printer_status_connecting to R.color.text_secondary
            KioskViewModel.PrinterStatus.FAILED -> R.string.kiosk_printer_status_failed to R.color.danger
            KioskViewModel.PrinterStatus.DISCONNECTED -> R.string.kiosk_printer_status_disconnected to R.color.text_secondary
        }
        binding.kioskPrinterStatus.text = getString(textRes, s.printerName)
        binding.kioskPrinterStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun handle(event: KioskViewModel.Event) {
        when (event) {
            is KioskViewModel.Event.Message -> Toast.makeText(this, event.text, Toast.LENGTH_LONG).show()
            is KioskViewModel.Event.TicketPrinted -> showTicketDialog(event.ticket, printed = true)
            is KioskViewModel.Event.TicketNotPrinted -> showTicketDialog(event.ticket, printed = false)
            KioskViewModel.Event.GoToPrinterSetup -> goToPrinterSetup(autoReconnect = true)
        }
    }

    /**
     * Printed: the ticket image with a countdown, then auto-close (fleQ DialogQueueCard).
     * Not printed: the same image with a Close button, so the patient can photograph it
     * (fleQ DialogQueueCardWithButton, "กรุณาถ่ายภาพบัตรคิว").
     */
    private fun showTicketDialog(ticket: Bitmap, printed: Boolean) {
        countdownJob?.cancel()
        ticketDialog?.dismiss()

        val db = DialogTicketBinding.inflate(layoutInflater)
        db.dialogTicketImage.setImageBitmap(ticket)
        db.dialogTicketMessage.setText(if (printed) R.string.kiosk_take_your_ticket else R.string.kiosk_print_failed_photo)
        db.dialogTicketMessage.setTextColor(ContextCompat.getColor(this, if (printed) R.color.text_primary else R.color.danger))

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(db.root)
            .setCancelable(!printed)
            .create()
        db.dialogTicketClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { countdownJob?.cancel() }
        dialog.show()
        ticketDialog = dialog

        db.dialogTicketCountdown.isVisible = printed
        if (printed) {
            countdownJob = lifecycleScope.launch {
                for (seconds in DIALOG_SECONDS downTo 1) {
                    db.dialogTicketCountdown.text = getString(R.string.kiosk_auto_close_in, seconds)
                    delay(1_000)
                }
                dialog.dismiss()
            }
        }
    }

    /** Keep the screen on only during opening hours (fleQ: AlarmReceiver + Utils.setKeepScreenOn). */
    private fun updateClockAndScreen() {
        val now = Calendar.getInstance()
        binding.kioskDate.text = dateFormat.format(now.time)
        val prefs = kiosk.prefs
        val open = WorkingHours.isOpen(now, prefs.openHour, prefs.closeHour)
        if (open) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        binding.kioskClosedBanner.isVisible = !open
        binding.kioskClosedBanner.text = getString(R.string.kiosk_closed_banner, prefs.openHour, prefs.closeHour)
    }

    private fun showAdminMenu() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_title)
            .setItems(arrayOf(getString(R.string.admin_change_printer), getString(R.string.admin_logout))) { _, which ->
                when (which) {
                    0 -> goToPrinterSetup(autoReconnect = false)
                    1 -> {
                        viewModel.logout()
                        Navigator.toLogin(this)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun goToPrinterSetup(autoReconnect: Boolean) {
        startActivity(
            Intent(this, PrinterSetupActivity::class.java)
                .putExtra(PrinterSetupActivity.EXTRA_AUTO_RECONNECT, autoReconnect)
        )
        finish()
    }

    private companion object {
        const val DIALOG_SECONDS = 5
        const val CLOCK_TICK_MS = 30_000L
    }
}
