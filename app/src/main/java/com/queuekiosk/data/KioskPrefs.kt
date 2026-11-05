package com.queuekiosk.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.queuekiosk.printer.escpos.BluetoothEscPosDriver
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * All persisted kiosk settings.
 *
 * Unlike fleQ (password and token in plain SharedPreferences), credentials live in
 * EncryptedSharedPreferences. The password is still stored, because the kiosk must be able to
 * re-login unattended after a reboot or token expiry, just as fleQ does.
 */
class KioskPrefs(context: Context) : SessionStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val secure: SharedPreferences = openSecurePrefs(context.applicationContext)

    // --- session (SessionStore) ---
    override var baseUrl by prefs.string("base_url")
    override var username by prefs.string("username")
    override var password by secure.string("password")
    override var token by secure.string("token")
    override var workflowId by prefs.int("workflow_id", -1)
    override var workflowName by prefs.string("workflow_name")
    override var branchName by prefs.string("branch_name")

    // --- kiosk options (fleQ login screen) ---
    var openHour by prefs.int("open_hour", 0)
    var closeHour by prefs.int("close_hour", 24)
    var showHistorySheet by prefs.boolean("show_history_sheet", false)
    var showTotalQueue by prefs.boolean("show_total_queue", true)
    var showQrCode by prefs.boolean("show_qr_code", false)

    // --- printer (fleQ: "selected_printer") ---
    var printerDriverId by prefs.string("printer_driver", BluetoothEscPosDriver.ID)
    var printerAddress by prefs.string("printer_address")
    var printerName by prefs.string("printer_name")
    var paperWidthPx by prefs.int("paper_width_px", PAPER_80MM_PX)

    val hasSession: Boolean get() = token.isNotBlank()
    val hasPrinter: Boolean get() = printerAddress.isNotBlank()

    /** Keeps URL/username for convenience, drops credentials. */
    fun clearSession() {
        token = ""
        password = ""
        workflowId = -1
    }

    companion object {
        const val PAPER_80MM_PX = 576
        const val PAPER_58MM_PX = 384

        private const val TAG = "KioskPrefs"
        private const val PREFS_FILE = "kiosk_prefs"
        private const val SECURE_PREFS_FILE = "kiosk_secure_prefs"

        private fun openSecurePrefs(context: Context): SharedPreferences = try {
            createSecurePrefs(context)
        } catch (e: Exception) {
            // The keystore can get out of sync with the file (e.g. after a restore or OS update).
            // Start fresh: the kiosk will fall back to the login screen.
            Log.w(TAG, "Encrypted prefs unreadable, recreating", e)
            context.deleteSharedPreferences(SECURE_PREFS_FILE)
            createSecurePrefs(context)
        }

        private fun createSecurePrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}

private fun SharedPreferences.string(key: String, default: String = "") =
    object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String =
            getString(key, default) ?: default

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) =
            edit { putString(key, value) }
    }

private fun SharedPreferences.int(key: String, default: Int) =
    object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = getInt(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) =
            edit { putInt(key, value) }
    }

private fun SharedPreferences.boolean(key: String, default: Boolean) =
    object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            edit { putBoolean(key, value) }
    }
