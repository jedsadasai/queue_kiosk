package com.queuekiosk.ui

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.radiobutton.MaterialRadioButton
import com.queuekiosk.R
import com.queuekiosk.data.KioskPrefs
import com.queuekiosk.databinding.ActivityPrinterSetupBinding
import kotlinx.coroutines.launch

/**
 * fleQ SetUpPrinterActivity. It scans only while Bluetooth is on and restarts or clears the scan
 * when Bluetooth is toggled (fleQ OnBluetoothEnableReceiver).
 */
class PrinterSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrinterSetupBinding
    private val viewModel: PrinterSetupViewModel by viewModels()
    private val adapter = PrinterDeviceAdapter { viewModel.select(it) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (KioskPermissions.granted(this)) startScanIfReady() else viewModel.onPermissionDenied()
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startScanIfReady() }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> startScanIfReady()
                BluetoothAdapter.STATE_OFF -> {
                    binding.setupEnableBluetooth.isVisible = true
                    viewModel.onBluetoothOff()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPrinterSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()

        if (intent.getBooleanExtra(EXTRA_AUTO_RECONNECT, false)) viewModel.startAutoReconnect()

        setUpDriverChoices()
        binding.setupPaperGroup.check(
            if (viewModel.state.value.paperWidthPx == KioskPrefs.PAPER_58MM_PX) R.id.setupPaper58 else R.id.setupPaper80
        )
        binding.setupPaperGroup.setOnCheckedChangeListener { _, checkedId ->
            viewModel.setPaperWidth(if (checkedId == R.id.setupPaper58) KioskPrefs.PAPER_58MM_PX else KioskPrefs.PAPER_80MM_PX)
        }

        binding.setupDevices.layoutManager = LinearLayoutManager(this)
        binding.setupDevices.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        binding.setupDevices.adapter = adapter

        binding.setupRescan.setOnClickListener { startScanIfReady() }
        binding.setupEnableBluetooth.setOnClickListener { requestEnableBluetooth() }
        binding.setupTestPrint.setOnClickListener { viewModel.testPrint() }
        binding.setupContinue.setOnClickListener { viewModel.continueToKiosk() }
        binding.setupBack.setOnClickListener { Navigator.toLogin(this) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            PrinterSetupViewModel.Event.GoToKiosk -> {
                                startActivity(Intent(this@PrinterSetupActivity, KioskActivity::class.java))
                                finish()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        startScanIfReady()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(bluetoothStateReceiver)
        viewModel.stopScan()
    }

    private fun startScanIfReady() {
        if (!KioskPermissions.granted(this)) {
            permissionLauncher.launch(KioskPermissions.required)
            return
        }
        val on = KioskPermissions.isBluetoothOn(this)
        binding.setupEnableBluetooth.isVisible = !on
        if (!on) {
            viewModel.onBluetoothOff()
            return
        }
        if (KioskPermissions.isLocationNeededButOff(this)) {
            // Android 10-11: paired printers are still listed, but new ones can't be found.
            Toast.makeText(this, R.string.setup_location_off, Toast.LENGTH_LONG).show()
        }
        viewModel.startScan()
    }

    private fun requestEnableBluetooth() {
        try {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: Exception) {
            // SecurityException without BLUETOOTH_CONNECT, or no handler on some kiosk ROMs
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (ignored: ActivityNotFoundException) {
            }
        }
    }

    private fun setUpDriverChoices() {
        val group = binding.setupDriverGroup
        viewModel.drivers.forEach { driver ->
            group.addView(MaterialRadioButton(this).apply {
                id = View.generateViewId()
                tag = driver.id
                text = driver.displayName
                isChecked = driver.id == viewModel.state.value.driverId
            })
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            (g.findViewById<View>(checkedId)?.tag as? String)?.let(viewModel::selectDriver)
        }
        // Only one driver in this build: nothing to choose.
        binding.setupDriverSection.isVisible = viewModel.drivers.size > 1
    }

    private fun render(s: PrinterSetupViewModel.UiState) {
        adapter.submit(s.devices, s.selected)
        binding.setupStatus.text = s.status
        binding.setupProgress.isVisible = s.scanning || s.busy
        binding.setupRescan.isEnabled = !s.scanning && !s.busy
        binding.setupSelected.text = s.selected?.let { getString(R.string.setup_selected_printer, it.name, it.address) }
            ?: getString(R.string.setup_no_printer_selected)
        binding.setupTestPrint.isEnabled = s.selected != null && !s.busy
        binding.setupContinue.isEnabled = s.selected != null && !s.busy
    }

    companion object {
        /** Set by the kiosk when it could not reach the printer: keep retrying, return when it works. */
        const val EXTRA_AUTO_RECONNECT = "auto_reconnect"
    }
}
