package com.queuekiosk.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.queuekiosk.R
import com.queuekiosk.data.ApiException
import com.queuekiosk.data.NetworkWaiter
import com.queuekiosk.databinding.ActivitySplashBinding
import com.queuekiosk.kiosk
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * fleQ SplashActivity: no token -> Login. Otherwise silently re-login with the stored
 * credentials (retrying every 5 s until the server answers), then go to the printer setup or kiosk screen.
 * This is what makes the kiosk recover by itself after a power cut.
 *
 * Android 17: if the "Nearby devices" permission (ACCESS_LOCAL_NETWORK) is missing, e.g. after
 * an upgrade from an older Android, ask first. Otherwise a LAN server is unreachable and re-login would retry forever.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private var reLoginJob: Job? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reLogin() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()
        enterImmersiveMode()

        binding.splashSettings.setOnLongClickListener {
            reLoginJob?.cancel()
            Navigator.toLogin(this)
            true
        }

        // Recreated (e.g. rotation): skip the delay, and don't re-ask if the permission dialog is
        // already up; its result is delivered to this new instance's launcher.
        val recreated = savedInstanceState != null
        // Opened by BootReceiver: no one is looking at a logo yet, so go straight to work.
        val fromBoot = intent.getBooleanExtra(EXTRA_FROM_BOOT, false)
        lifecycleScope.launch {
            if (!recreated && !fromBoot) delay(SPLASH_MS)
            when {
                !kiosk.prefs.hasSession -> Navigator.toLogin(this@SplashActivity)
                KioskPermissions.granted(this@SplashActivity) -> reLogin()
                !recreated -> permissionLauncher.launch(KioskPermissions.required)
            }
        }
    }

    private fun reLogin() {
        if (!kiosk.prefs.hasSession || reLoginJob?.isActive == true) return
        binding.splashStatus.setText(R.string.splash_signing_in)
        val network = NetworkWaiter(this)
        reLoginJob = lifecycleScope.launch {
            try {
                kiosk.auth.reLoginWithRetry(
                    // After power-on Wi-Fi is often still connecting: wait for it rather than fail
                    // and sleep. If it never comes, try anyway so the error is shown and retried.
                    beforeAttempt = {
                        if (!network.isConnected()) {
                            binding.splashStatus.setText(R.string.splash_waiting_network)
                            network.awaitNetwork(NETWORK_WAIT_MS)
                            binding.splashStatus.setText(R.string.splash_signing_in)
                        }
                    },
                    onRetry = { e ->
                        binding.splashStatus.text = getString(R.string.splash_retrying, e.userMessage(this@SplashActivity))
                    },
                )
                Navigator.routeAfterAuth(this@SplashActivity)
            } catch (e: ApiException) {
                Toast.makeText(this@SplashActivity, e.userMessage(this@SplashActivity), Toast.LENGTH_LONG).show()
                Navigator.toLogin(this@SplashActivity)
            }
        }
    }

    companion object {
        /** Set by [com.queuekiosk.BootReceiver]: skips the splash delay. */
        const val EXTRA_FROM_BOOT = "from_boot"
        private const val SPLASH_MS = 1_000L
        private const val NETWORK_WAIT_MS = 30_000L
    }
}
