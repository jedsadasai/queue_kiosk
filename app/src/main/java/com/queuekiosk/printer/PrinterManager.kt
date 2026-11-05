package com.queuekiosk.printer

import android.graphics.Bitmap
import android.util.Log
import com.queuekiosk.data.KioskPrefs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns "the" kiosk printer: which one is selected, the open connection, and a print job queue.
 *
 * Every operation runs under one [Mutex], so print jobs are queued and executed one at a time,
 * and a connect never races a print. fleQ had no job queue. It only disabled the button while printing.
 */
class PrinterManager(
    private val registry: PrinterRegistry,
    private val prefs: KioskPrefs,
) {
    private val mutex = Mutex()
    private var driver: PrinterDriver? = null
    private var connectedDevice: PrinterDevice? = null

    /** fleQ: RajthaneeSharedPreferences.getSelectedPrinter(). */
    fun savedDevice(): PrinterDevice? {
        val address = prefs.printerAddress.takeIf { it.isNotBlank() } ?: return null
        return PrinterDevice(
            name = prefs.printerName.ifBlank { address },
            address = address,
            driverId = prefs.printerDriverId,
        )
    }

    /** Persists [device] as the kiosk printer (and drops any connection to a different one). */
    suspend fun select(device: PrinterDevice) = mutex.withLock {
        if (connectedDevice != device) disconnectLocked()
        prefs.printerDriverId = device.driverId
        prefs.printerAddress = device.address
        prefs.printerName = device.name
    }

    /** fleQ: MainActivity.doConnectPrinter() in onStart. */
    suspend fun connectSaved() {
        mutex.withLock { ensureConnectedLocked() }
    }

    /**
     * Queues a print job. If the link has dropped since connecting (printer turned off and on,
     * out of range...), it reconnects once and retries.
     */
    suspend fun print(bitmap: Bitmap) {
        mutex.withLock {
            try {
                ensureConnectedLocked().print(bitmap)
            } catch (first: PrinterException) {
                Log.w(TAG, "Print failed, reconnecting once", first)
                disconnectLocked()
                try {
                    ensureConnectedLocked().print(bitmap)
                } catch (second: PrinterException) {
                    second.addSuppressed(first)
                    throw second
                }
            }
        }
    }

    /** fleQ: MainActivity.doDisconnectPrinter() in onStop. */
    suspend fun disconnect() {
        mutex.withLock { disconnectLocked() }
    }

    private suspend fun ensureConnectedLocked(): PrinterDriver {
        val device = savedDevice() ?: throw PrinterException("No printer selected")
        val target = registry.get(device.driverId)
            ?: throw PrinterException("Printer driver '${device.driverId}' is not available in this build")

        if (driver === target && connectedDevice == device && target.isConnected) return target

        disconnectLocked()
        target.connect(device)
        driver = target
        connectedDevice = device
        return target
    }

    private suspend fun disconnectLocked() {
        val d = driver ?: return
        driver = null
        connectedDevice = null
        withContext(NonCancellable) {
            try {
                d.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Disconnect failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "PrinterManager"
    }
}
