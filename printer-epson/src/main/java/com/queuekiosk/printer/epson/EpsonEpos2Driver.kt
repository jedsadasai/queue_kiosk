package com.queuekiosk.printer.epson

import android.content.Context
import android.graphics.Bitmap
import com.epson.epos2.Epos2Exception
import com.epson.epos2.discovery.DeviceInfo
import com.epson.epos2.discovery.Discovery
import com.epson.epos2.discovery.DiscoveryListener
import com.epson.epos2.discovery.FilterOption
import com.epson.epos2.printer.Printer
import com.epson.epos2.printer.ReceiveListener
import com.queuekiosk.printer.PrinterDevice
import com.queuekiosk.printer.PrinterDriver
import com.queuekiosk.printer.PrinterException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Epson TM printers via the ePOS2 SDK. This is a port of what fleQ_KIOSK does in
 * SetUpPrinterActivity (discovery) and MainActivity (connect/print/disconnect).
 *
 * The numeric constants below were read from the SDK bundled in fleQ
 * (com/epson/epos2/printer/CommonPrinter.java, Printer.java, Epos2CallbackCode.java). They are
 * declared locally because older SDKs put them on CommonPrinter and newer ones on Printer.
 *
 * Loaded reflectively by PrinterRegistry, so it needs the (Context) constructor.
 */
class EpsonEpos2Driver @JvmOverloads constructor(
    context: Context,
    private val series: Int = TM_M30,
    private val langModel: Int = MODEL_SOUTHASIA,
) : PrinterDriver {

    override val id: String = ID
    override val displayName: String = "Epson TM (ePOS2 SDK)"

    private val appContext = context.applicationContext

    @Volatile private var printer: Printer? = null
    @Volatile private var pendingJob: CompletableDeferred<Int>? = null

    override val isConnected: Boolean get() = printer != null

    /** fleQ: SetUpPrinterActivity.doDiscoveryDevice() with PORTTYPE_BLUETOOTH. */
    override fun discover(): Flow<PrinterDevice> = callbackFlow {
        val filter = FilterOption().apply {
            setPortType(Discovery.PORTTYPE_BLUETOOTH)
            setBroadcast("255.255.255.255")
            setDeviceModel(Discovery.MODEL_ALL)
            setEpsonFilter(Discovery.FILTER_NAME)
            setDeviceType(Discovery.TYPE_PRINTER)
        }
        val listener = DiscoveryListener { info: DeviceInfo ->
            val target = info.target ?: return@DiscoveryListener
            trySend(PrinterDevice(name = info.deviceName ?: target, address = target, driverId = ID))
        }
        try {
            Discovery.start(appContext, filter, listener)
        } catch (e: Epos2Exception) {
            close(PrinterException("Epson discovery failed (status ${e.errorStatus})", e))
            return@callbackFlow
        }
        awaitClose { stopDiscovery() }
    }

    /** fleQ: MainActivity.initializeObject() + connectPrinter(). */
    override suspend fun connect(device: PrinterDevice) {
        withContext(Dispatchers.IO) {
            disconnectInternal()
            val p = try {
                Printer(series, langModel, appContext)
            } catch (e: Epos2Exception) {
                throw PrinterException("Cannot create Epson printer object (status ${e.errorStatus})", e)
            }
            // fleQ: onPtrReceive -> clearCommandBuffer. We also use it to learn whether the job succeeded.
            p.setReceiveEventListener(ReceiveListener { _, code, _, _ -> pendingJob?.complete(code) })
            try {
                p.connect(device.address, PARAM_DEFAULT)
            } catch (e: Epos2Exception) {
                p.setReceiveEventListener(null)
                throw PrinterException("Cannot connect to ${device.name} (status ${e.errorStatus})", e)
            }
            try {
                p.beginTransaction()
            } catch (ignored: Epos2Exception) {
                // fleQ also carries on without a transaction; printing still works.
            }
            printer = p
        }
    }

    /** fleQ: MainActivity.printQueueCardFromImage() + printData(). */
    override suspend fun print(bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            val p = printer ?: throw PrinterException("Epson printer not connected")

            // fleQ isPrintable(): connection != FALSE && online != FALSE
            val status = p.status
            if (status == null || status.connection == FALSE || status.online == FALSE) {
                disconnectInternal()
                throw PrinterException(
                    "Epson printer is offline (connection=${status?.connection}, online=${status?.online})"
                )
            }

            val job = CompletableDeferred<Int>().also { pendingJob = it }
            try {
                p.clearCommandBuffer()
                p.addTextAlign(ALIGN_CENTER)
                p.addImage(
                    bitmap, 0, 0, bitmap.width, bitmap.height,
                    COLOR_1, MODE_MONO_HIGH_DENSITY, HALFTONE_DITHER, PARAM_DEFAULT.toDouble(), COMPRESS_AUTO,
                )
                p.addFeedLine(1)
                p.addCut(CUT_FEED)
                p.sendData(PARAM_DEFAULT)
            } catch (e: Epos2Exception) {
                pendingJob = null
                p.clearCommandBuffer()
                throw PrinterException("Epson print failed (status ${e.errorStatus})", e)
            }

            // fleQ treated sendData() returning as success. We wait for the printer's verdict.
            val code = withTimeoutOrNull(RESULT_TIMEOUT_MS) { job.await() }
            pendingJob = null
            p.clearCommandBuffer()
            when (code) {
                CODE_SUCCESS -> Unit
                null -> throw PrinterException("Timed out waiting for the Epson print result")
                else -> throw PrinterException("Epson printer reported error code $code")
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { disconnectInternal() }
    }

    /** fleQ: MainActivity.disconnectPrinter() + finalizeObject(). */
    private fun disconnectInternal() {
        val p = printer ?: return
        printer = null
        pendingJob?.cancel()
        pendingJob = null
        runCatching { p.endTransaction() }
        runCatching { p.disconnect() }
        p.clearCommandBuffer()
        p.setReceiveEventListener(null)
    }

    /** fleQ: retries Discovery.stop() while the SDK reports ERR_PROCESSING. */
    private fun stopDiscovery() {
        repeat(STOP_RETRIES) {
            try {
                Discovery.stop()
                return
            } catch (e: Epos2Exception) {
                if (e.errorStatus != Epos2Exception.ERR_PROCESSING) return
                Thread.sleep(50)
            }
        }
    }

    companion object {
        const val ID = "epson"

        // Printer series / language model (fleQ used new Printer(1, 6, ctx))
        const val TM_M30 = 1
        const val MODEL_SOUTHASIA = 6

        // CommonPrinter constants
        private const val PARAM_DEFAULT = -2
        private const val FALSE = 0
        private const val ALIGN_CENTER = 1
        private const val COLOR_1 = 1
        private const val MODE_MONO_HIGH_DENSITY = 2
        private const val HALFTONE_DITHER = 0
        private const val COMPRESS_AUTO = 2
        private const val CUT_FEED = 1

        // Epos2CallbackCode
        private const val CODE_SUCCESS = 0

        private const val RESULT_TIMEOUT_MS = 30_000L
        private const val STOP_RETRIES = 20
    }
}
