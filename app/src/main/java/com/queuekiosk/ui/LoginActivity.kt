package com.queuekiosk.ui

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.queuekiosk.R
import com.queuekiosk.databinding.ActivityLoginBinding
import com.queuekiosk.kiosk
import kotlinx.coroutines.launch

/**
 * fleQ LoginActivity: server URL, username/password, opening hours and ticket options.
 *
 * The runtime permissions are requested *before* the login call: on Android 17 the call itself
 * needs ACCESS_LOCAL_NETWORK when the server is on the LAN. The same "Nearby devices" prompt
 * also grants the Bluetooth permissions needed afterwards for the printer.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels()

    // Same choices as fleQ: open 0:00-12:00, close 13:00-24:00
    private val openHours = (0..12).toList()
    private val closeHours = (13..24).toList()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Log in even if denied: an Internet server still works, and a LAN server fails with a
            // message explaining the missing permission. Printer setup asks again for Bluetooth.
            viewModel.login(readForm())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(includeIme = true)

        val prefs = kiosk.prefs
        if (savedInstanceState == null) {
            binding.loginUrl.setText(prefs.baseUrl)
            binding.loginUsername.setText(prefs.username)
            binding.loginShowHistory.isChecked = prefs.showHistorySheet
            binding.loginShowTotal.isChecked = prefs.showTotalQueue
            binding.loginShowQr.isChecked = prefs.showQrCode
        }

        binding.loginOpenHour.adapter = hourAdapter(openHours)
        binding.loginCloseHour.adapter = hourAdapter(closeHours)
        binding.loginOpenHour.setSelection(openHours.indexOf(prefs.openHour).coerceAtLeast(0))
        binding.loginCloseHour.setSelection(closeHours.indexOf(prefs.closeHour).let { if (it < 0) closeHours.lastIndex else it })

        binding.loginButton.setOnClickListener { submit() }
        binding.loginPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { s ->
                        binding.loginButton.isEnabled = !s.loading
                        binding.loginProgress.isVisible = s.loading
                        binding.loginStatus.text = s.status
                        binding.loginStatus.setTextColor(
                            ContextCompat.getColor(this@LoginActivity, if (s.isError) R.color.danger else R.color.text_secondary)
                        )
                    }
                }
                launch { viewModel.loggedIn.collect { onLoggedIn() } }
            }
        }
    }

    private fun submit() {
        if (KioskPermissions.granted(this)) {
            viewModel.login(readForm())
        } else {
            permissionLauncher.launch(KioskPermissions.required)
        }
    }

    private fun readForm() = LoginForm(
        url = binding.loginUrl.text.toString(),
        username = binding.loginUsername.text.toString(),
        password = binding.loginPassword.text.toString(),
        openHour = openHours[binding.loginOpenHour.selectedItemPosition],
        closeHour = closeHours[binding.loginCloseHour.selectedItemPosition],
        showHistorySheet = binding.loginShowHistory.isChecked,
        showTotalQueue = binding.loginShowTotal.isChecked,
        showQrCode = binding.loginShowQr.isChecked,
    )

    private fun onLoggedIn() = Navigator.routeAfterAuth(this)

    private fun hourAdapter(hours: List<Int>) = ArrayAdapter(
        this,
        android.R.layout.simple_spinner_item,
        hours.map { getString(R.string.login_hour_format, it) },
    ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
}
