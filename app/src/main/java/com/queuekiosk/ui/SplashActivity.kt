package com.queuekiosk.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.queuekiosk.R
import com.queuekiosk.data.ApiException
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
        lifecycleScope.launch {
            if (!recreated) delay(SPLASH_MS)
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
        reLoginJob = lifecycleScope.launch {
            try {
                kiosk.auth.reLoginWithRetry(onRetry = { e ->
                    binding.splashStatus.text = getString(R.string.splash_retrying, e.userMessage(this@SplashActivity))
                })
                Navigator.routeAfterAuth(this@SplashActivity)
            } catch (e: ApiException) {
                Toast.makeText(this@SplashActivity, e.userMessage(this@SplashActivity), Toast.LENGTH_LONG).show()
                Navigator.toLogin(this@SplashActivity)
            }
        }
    }

    private companion object {
        const val SPLASH_MS = 1_000L
    }
}
