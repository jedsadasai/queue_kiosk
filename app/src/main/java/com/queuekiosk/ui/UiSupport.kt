package com.queuekiosk.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.queuekiosk.R
import com.queuekiosk.data.ApiException
import com.queuekiosk.kiosk

/**
 * Runtime permissions the kiosk needs, per Android version (minSdk 29 = Android 10, target 37 = Android 17).
 *
 * | Android      | Bluetooth discovery/connect           | LAN server (http://192.168.x.x) |
 * |--------------|---------------------------------------|---------------------------------|
 * | 10-11 (29-30)| ACCESS_FINE_LOCATION (+ Location on)  | nothing (INTERNET)              |
 * | 12-16 (31-36)| BLUETOOTH_SCAN + BLUETOOTH_CONNECT    | nothing (INTERNET)              |
 * | 17+  (37+)   | BLUETOOTH_SCAN + BLUETOOTH_CONNECT    | ACCESS_LOCAL_NETWORK            |
 *
 * On 12+ these are all in the "Nearby devices" group, so the user sees one prompt.
 */
object KioskPermissions {

    /** Android 17 = API 37 (Build.VERSION_CODES.CINNAMON_BUN). */
    private const val ANDROID_17 = 37

    val required: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                // Classic discovery needs location on Android 10-11 (fleQ asked for ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= ANDROID_17) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }.toTypedArray()

    fun granted(context: Context): Boolean = required.all { isGranted(context, it) }

    /** False only on Android 17+ when the user denied "Nearby devices": LAN traffic then silently times out. */
    fun localNetworkGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < ANDROID_17 || isGranted(context, Manifest.permission.ACCESS_LOCAL_NETWORK)

    fun isBluetoothOn(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /**
     * Android 10-11 return no inquiry results while Location is switched off
     * (paired printers are still listed). From Android 12 BLUETOOTH_SCAN replaces this.
     */
    fun isLocationNeededButOff(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return !LocationManagerCompat.isLocationEnabled(lm)
    }

    private fun isGranted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

object Navigator {
    /** fleQ Utils.checkSelectedPrinter: no printer saved -> printer setup, otherwise the kiosk screen. */
    fun routeAfterAuth(activity: Activity) {
        val ready = activity.kiosk.prefs.hasPrinter && KioskPermissions.granted(activity)
        val target = if (ready) KioskActivity::class.java else PrinterSetupActivity::class.java
        activity.startActivity(Intent(activity, target))
        activity.finish()
    }

    fun toLogin(activity: Activity) {
        activity.startActivity(
            Intent(activity, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
    }
}

/** fleQ Utils.hideSystemUI: full-screen kiosk. */
fun Activity.enterImmersiveMode() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/**
 * Edge-to-edge is enforced from target SDK 35 (Android 15) and can't be opted out of from 36.
 * Pads this view so content stays clear of the status/navigation bars, display cutout and,
 * if [includeIme], the keyboard (adjustResize no longer resizes edge-to-edge windows).
 * The view's own XML padding is preserved.
 */
fun View.applySystemBarsPadding(includeIme: Boolean = false) {
    val start = paddingLeft
    val top = paddingTop
    val end = paddingRight
    val bottom = paddingBottom
    val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
        (if (includeIme) WindowInsetsCompat.Type.ime() else 0)
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
        val insets = windowInsets.getInsets(types)
        v.updatePadding(
            left = start + insets.left,
            top = top + insets.top,
            right = end + insets.right,
            bottom = bottom + insets.bottom,
        )
        windowInsets
    }
    ViewCompat.requestApplyInsets(this)
}

fun ApiException.userMessage(context: Context): String {
    // Android 17: without "Nearby devices" a LAN server is unreachable and calls just time out.
    if (kind == ApiException.Kind.NETWORK && !KioskPermissions.localNetworkGranted(context)) {
        return context.getString(R.string.error_local_network_permission)
    }
    return context.getString(
        when (kind) {
            ApiException.Kind.INVALID_URL -> R.string.error_invalid_url
            ApiException.Kind.BAD_CREDENTIALS -> R.string.error_bad_credentials
            ApiException.Kind.UNAUTHORIZED -> R.string.error_unauthorized
            ApiException.Kind.SERVER -> R.string.error_server
            ApiException.Kind.NETWORK -> R.string.error_network
            ApiException.Kind.BAD_RESPONSE -> R.string.error_bad_response
            ApiException.Kind.NOT_LOGGED_IN -> R.string.error_not_logged_in
        }
    )
}
