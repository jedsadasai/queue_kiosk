package com.queuekiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.queuekiosk.ui.SplashActivity

/**
 * fleQ OnDeviceBootReceiver: opens the kiosk when the tablet is switched on.
 *
 * SplashActivity then re-logs in by itself (retrying every 5 s until Wi-Fi and the server are up)
 * and goes on to the kiosk screen, so the kiosk recovers from a power cut without anyone touching it.
 *
 * Android 10+ blocks activity starts from the background. A receiver may still start one if the
 * user has granted "Display over other apps" (SYSTEM_ALERT_WINDOW), which the login screen asks for.
 * BOOT_COMPLETED arrives only once the device is unlocked, so kiosks should use screen lock "None".
 *
 * BOOT_COMPLETED is delivered to apps one at a time, highest priority first. On a Samsung Tab A7
 * an app at the default priority waited over a minute for its turn, so the manifest gives this
 * receiver priority 999 (the highest an ordinary app may use).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        if (!context.kiosk.prefs.autoStartOnBoot) return
        Log.i(TAG, "${intent.action} received ${SystemClock.elapsedRealtime()} ms after boot, opening the kiosk")

        if (!AutoStart.canStartFromBackground(context)) {
            Log.w(TAG, "\"Display over other apps\" is not granted: Android may block the auto start")
        }
        try {
            context.startActivity(
                Intent(context, SplashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(SplashActivity.EXTRA_FROM_BOOT, true)
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not open the kiosk after boot", e)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON", // some OEMs (fleQ listened for it too)
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}

/** The permission Android 10+ requires before [BootReceiver] may bring the app to the front. */
object AutoStart {
    fun canStartFromBackground(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Opens this app's "Display over other apps" page in Settings. */
    fun permissionSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
}
