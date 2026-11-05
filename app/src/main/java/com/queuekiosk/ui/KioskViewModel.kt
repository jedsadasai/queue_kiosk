package com.queuekiosk.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.queuekiosk.R
import com.queuekiosk.data.ApiException
import com.queuekiosk.data.WorkingHours
import com.queuekiosk.kiosk
import com.queuekiosk.printer.PrinterException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * fleQ MainActivity: keep the printer connected while visible; on "กดคิว" create a queue
 * number on the server, render the ticket, print it, and show it on screen.
 */
class KioskViewModel(app: Application) : AndroidViewModel(app) {

    enum class PrinterStatus { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    data class UiState(
        val printer: PrinterStatus = PrinterStatus.DISCONNECTED,
        val printerName: String = "",
        val busy: Boolean = false,
    )

    sealed interface Event {
        data class Message(val text: String) : Event
        /** Ticket printed: show it with an auto-dismiss countdown (fleQ DialogQueueCard). */
        data class TicketPrinted(val ticket: Bitmap) : Event
        /** Number was created but printing failed: show it with a Close button (fleQ DialogQueueCardWithButton). */
        data class TicketNotPrinted(val ticket: Bitmap) : Event
        data object GoToPrinterSetup : Event
    }

    private val kiosk = app.kiosk
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var connectJob: Job? = null

    /** fleQ MainActivity.onStart -> doConnectPrinter(). fleQ went to setup on the first failure; we retry once. */
    fun connectPrinter() {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _state.update {
                it.copy(printer = PrinterStatus.CONNECTING, printerName = kiosk.printers.savedDevice()?.name.orEmpty())
            }
            repeat(CONNECT_ATTEMPTS) { attempt ->
                try {
                    kiosk.printers.connectSaved()
                    _state.update { it.copy(printer = PrinterStatus.CONNECTED) }
                    _events.send(Event.Message(str(R.string.kiosk_printer_connected)))
                    return@launch
                } catch (e: PrinterException) {
                    Log.w(TAG, "Printer connect attempt ${attempt + 1} failed", e)
                    if (attempt < CONNECT_ATTEMPTS - 1) delay(CONNECT_RETRY_DELAY_MS)
                }
            }
            _state.update { it.copy(printer = PrinterStatus.FAILED) }
            _events.send(Event.Message(str(R.string.kiosk_printer_connect_failed)))
            _events.send(Event.GoToPrinterSetup)
        }
    }

    /** fleQ MainActivity.onStop -> doDisconnectPrinter(). Runs in the app scope, so it survives the screen closing. */
    fun disconnectPrinter() {
        connectJob?.cancel()
        _state.update { it.copy(printer = PrinterStatus.DISCONNECTED) }
        kiosk.appScope.launch { kiosk.printers.disconnect() }
    }

    /** The "กดคิว" button. */
    fun takeQueue() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val prefs = kiosk.prefs
                if (!WorkingHours.isOpen(Calendar.getInstance(), prefs.openHour, prefs.closeHour)) {
                    _events.send(Event.Message(str(R.string.kiosk_out_of_hours)))
                    return@launch
                }

                val ticket = try {
                    kiosk.queue.addQueue()
                } catch (e: ApiException) {
                    Log.w(TAG, "add-queue failed", e)
                    _events.send(Event.Message(str(R.string.kiosk_cannot_create_queue) + "\n" + e.userMessage(getApplication<Application>())))
                    return@launch
                }

                val bitmap = kiosk.ticketRenderer.render(ticket, kiosk.ticketOptions())
                try {
                    kiosk.printers.print(bitmap)
                    _state.update { it.copy(printer = PrinterStatus.CONNECTED) }
                    _events.send(Event.TicketPrinted(bitmap))
                } catch (e: PrinterException) {
                    Log.w(TAG, "Print failed for queue ${ticket.number}", e)
                    _state.update { it.copy(printer = PrinterStatus.FAILED) }
                    _events.send(Event.TicketNotPrinted(bitmap))
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun logout() {
        kiosk.prefs.clearSession()
        disconnectPrinter()
    }

    private fun str(@StringRes res: Int): String = getApplication<Application>().getString(res)

    private companion object {
        const val TAG = "KioskViewModel"
        const val CONNECT_ATTEMPTS = 2
        const val CONNECT_RETRY_DELAY_MS = 3_000L
    }
}
