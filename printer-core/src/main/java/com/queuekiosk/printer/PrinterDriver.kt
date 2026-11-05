package com.queuekiosk.printer

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow

/**
 * A printer found during discovery.
 *
 * @param address what the driver needs to connect: a Bluetooth MAC for ESC/POS,
 *                or an ePOS2 target such as `BT:00:01:90:AA:BB:CC` for Epson.
 */
data class PrinterDevice(
    val name: String,
    val address: String,
    val driverId: String,
)

/** Every failure a driver can surface: discovery, connect or print. */
class PrinterException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One way of talking to a receipt printer.
 *
 * fleQ_KIOSK hard-wired the Epson ePOS2 SDK into MainActivity/SetUpPrinterActivity. Here the
 * same lifecycle (discover -> connect -> print image -> disconnect) sits behind an interface,
 * so generic ESC/POS printers and Epson printers are interchangeable.
 *
 * Implementations must have a public constructor taking a single [android.content.Context]
 * if they are loaded reflectively (as the optional Epson module is).
 */
interface PrinterDriver {
    /** Stable id persisted in preferences, e.g. "escpos" or "epson". */
    val id: String

    /** Human-readable name shown in the printer setup screen. */
    val displayName: String

    val isConnected: Boolean

    /**
     * Emits printers as they are found. The flow may run until cancelled; it may also
     * complete by itself, or fail with [PrinterException] (e.g. Bluetooth off, permission missing).
     */
    fun discover(): Flow<PrinterDevice>

    /** Opens a connection. Any previous connection held by this driver is closed first. */
    suspend fun connect(device: PrinterDevice)

    /** Prints [bitmap] centred, then feeds and cuts. Throws [PrinterException] on failure. */
    suspend fun print(bitmap: Bitmap)

    /** Closes the connection. Never throws. */
    suspend fun disconnect()
}
