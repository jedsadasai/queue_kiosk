package com.queuekiosk.printer.escpos

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.queuekiosk.printer.PrinterDevice
import com.queuekiosk.printer.PrinterDriver
import com.queuekiosk.printer.PrinterException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Generic Bluetooth thermal printer: Classic Bluetooth SPP (RFCOMM) socket + ESC/POS raster image.
 * Works with most 58/80 mm Bluetooth receipt printers, and with Epson TM printers in ESC/POS mode.
 *
 * Callers must hold the runtime Bluetooth permissions (see ui/KioskPermissions). Every
 * SecurityException is turned into a [PrinterException].
 */
@SuppressLint("MissingPermission")
class BluetoothEscPosDriver(context: Context) : PrinterDriver {

    override val id: String = ID
    override val displayName: String = "Bluetooth ESC/POS (generic)"

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter?
        get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile private var socket: BluetoothSocket? = null

    override val isConnected: Boolean get() = socket?.isConnected == true

    /** Paired devices first, then devices found by an inquiry scan. Completes when the scan finishes. */
    override fun discover(): Flow<PrinterDevice> = callbackFlow {
        val bt = adapter
        if (bt == null) {
            close(PrinterException("This device has no Bluetooth"))
            return@callbackFlow
        }
        if (!bt.isEnabled) {
            close(PrinterException("Bluetooth is off"))
            return@callbackFlow
        }

        val seen = HashSet<String>()
        fun offer(device: BluetoothDevice) {
            if (!seen.add(device.address)) return
            val name = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: device.address
            trySend(PrinterDevice(name = name, address = device.address, driverId = ID))
        }

        try {
            bt.bondedDevices.orEmpty().forEach(::offer)
        } catch (e: SecurityException) {
            close(PrinterException("Bluetooth permission not granted", e))
            return@callbackFlow
        }

        val receiver = object : BroadcastReceiver() {
            // cancelDiscovery() of an earlier scan can deliver a late FINISHED; only honour ours.
            private var started = false

            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND ->
                        IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            ?.let(::offer)
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> started = true
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (started) channel.close()
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // System (protected) broadcasts; exported is required to receive them on Android 14.
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        val started = try {
            bt.cancelDiscovery()
            bt.startDiscovery()
        } catch (e: SecurityException) {
            false
        }
        // Scanning can fail (e.g. location services off on Android <= 11). Paired devices are still listed.
        if (!started) channel.close()

        awaitClose {
            runCatching { bt.cancelDiscovery() }
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    override suspend fun connect(device: PrinterDevice) {
        withContext(Dispatchers.IO) {
            closeSocket()
            val bt = adapter ?: throw PrinterException("This device has no Bluetooth")
            if (!bt.isEnabled) throw PrinterException("Bluetooth is off")

            try {
                // An active inquiry scan makes RFCOMM connects slow and flaky.
                bt.cancelDiscovery()
                val remote = try {
                    bt.getRemoteDevice(device.address)
                } catch (e: IllegalArgumentException) {
                    throw PrinterException("Invalid Bluetooth address ${device.address}", e)
                }
                socket = try {
                    openSocket { remote.createRfcommSocketToServiceRecord(SPP_UUID) }
                } catch (secure: IOException) {
                    // Some cheap printers only accept insecure (unauthenticated) RFCOMM.
                    try {
                        openSocket { remote.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
                    } catch (insecure: IOException) {
                        insecure.addSuppressed(secure)
                        throw PrinterException("Could not connect to ${device.name}: ${insecure.message}", insecure)
                    }
                }
            } catch (e: SecurityException) {
                throw PrinterException("Bluetooth permission not granted", e)
            }
        }
    }

    override suspend fun print(bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            val s = socket?.takeIf { it.isConnected } ?: throw PrinterException("Printer not connected")

            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val data = EscPosEncoder.encodeImage(pixels, bitmap.width, bitmap.height)

            try {
                val out = s.outputStream
                var offset = 0
                while (offset < data.size) {
                    val len = minOf(CHUNK_BYTES, data.size - offset)
                    out.write(data, offset, len)
                    out.flush()
                    offset += len
                    // Small printers have tiny input buffers and no flow control over SPP.
                    delay(CHUNK_DELAY_MS)
                }
            } catch (e: IOException) {
                closeSocket()
                throw PrinterException("Print failed: ${e.message}", e)
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { closeSocket() }
    }

    private inline fun openSocket(create: () -> BluetoothSocket): BluetoothSocket {
        val s = create()
        try {
            s.connect()
            return s
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
    }

    private fun closeSocket() {
        val s = socket ?: return
        socket = null
        runCatching { s.close() }
    }

    companion object {
        const val ID = "escpos"

        /** Standard Serial Port Profile UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val CHUNK_BYTES = 1024
        private const val CHUNK_DELAY_MS = 20L
    }
}
