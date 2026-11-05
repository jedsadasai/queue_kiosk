package com.queuekiosk.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.queuekiosk.R
import com.queuekiosk.data.QueueTicket
import com.queuekiosk.kiosk
import com.queuekiosk.printer.PrinterDevice
import com.queuekiosk.printer.PrinterDriver
import com.queuekiosk.printer.PrinterException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * fleQ SetUpPrinterActivity + DeviceAdapter: scan, list printers, pick one.
 * Additions: choice of driver (ESC/POS or Epson), paper width, and a test print.
 */
class PrinterSetupViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val driverId: String,
        val devices: List<PrinterDevice> = emptyList(),
        val selected: PrinterDevice? = null,
        val paperWidthPx: Int,
        val scanning: Boolean = false,
        val busy: Boolean = false,
        val status: String = "",
    )

    sealed interface Event {
        data object GoToKiosk : Event
    }

    private val kiosk = app.kiosk
    val drivers: List<PrinterDriver> = kiosk.printerRegistry.drivers

    private val _state = MutableStateFlow(
        UiState(
            driverId = kiosk.prefs.printerDriverId.takeIf { id -> drivers.any { it.id == id } } ?: drivers.first().id,
            selected = kiosk.printers.savedDevice(),
            paperWidthPx = kiosk.prefs.paperWidthPx,
        )
    )
    val state = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var scanJob: Job? = null
    private var autoReconnectJob: Job? = null

    /**
     * fleQ's DeviceAdapter jumped back to the kiosk as soon as discovery listed the saved printer.
     * That loops with ESC/POS, because paired devices are listed even while the printer is off.
     * Instead, keep trying to really connect to the saved printer in the background, and return to
     * the kiosk once that works. Used when the kiosk sent us here because the printer was unreachable.
     */
    fun startAutoReconnect() {
        if (autoReconnectJob?.isActive == true || kiosk.printers.savedDevice() == null) return
        autoReconnectJob = viewModelScope.launch {
            while (true) {
                delay(AUTO_RECONNECT_INTERVAL_MS)
                // Never fight an inquiry scan or a test print for the radio.
                if (_state.value.scanning || _state.value.busy) continue
                try {
                    kiosk.printers.connectSaved()
                    _events.send(Event.GoToKiosk)
                    return@launch
                } catch (ignored: PrinterException) {
                    // printer still off / out of range: try again later
                }
            }
        }
    }

    private fun stopAutoReconnect() {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
    }

    fun selectDriver(id: String) {
        if (id == _state.value.driverId) return
        _state.update { it.copy(driverId = id) }
        startScan()
    }

    fun setPaperWidth(px: Int) {
        kiosk.prefs.paperWidthPx = px
        _state.update { it.copy(paperWidthPx = px) }
    }

    fun startScan() {
        val driver = kiosk.printerRegistry.get(_state.value.driverId) ?: return
        scanJob?.cancel()
        _state.update { it.copy(devices = emptyList(), scanning = true, status = str(R.string.setup_scanning)) }
        scanJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                    driver.discover().collect { onFound(it) }
                }
                _state.update {
                    it.copy(
                        scanning = false,
                        status = str(if (it.devices.isEmpty()) R.string.setup_no_devices else R.string.setup_pick_printer),
                    )
                }
            } catch (e: PrinterException) {
                _state.update { it.copy(scanning = false, status = e.message.orEmpty()) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scanning = false) }
    }

    /** fleQ OnBluetoothEnableReceiver, STATE_OFF: clear the list and explain. */
    fun onBluetoothOff() {
        stopScan()
        _state.update { it.copy(devices = emptyList(), status = str(R.string.setup_bluetooth_off)) }
    }

    fun onPermissionDenied() {
        stopScan()
        _state.update { it.copy(status = str(R.string.setup_permission_denied)) }
    }

    fun select(device: PrinterDevice) {
        stopAutoReconnect()
        stopScan()
        viewModelScope.launch {
            kiosk.printers.select(device)
            _state.update { it.copy(selected = device, status = str(R.string.setup_selected_hint)) }
        }
    }

    fun testPrint() {
        if (_state.value.selected == null || _state.value.busy) return
        stopAutoReconnect()
        stopScan()
        _state.update { it.copy(busy = true, status = str(R.string.setup_test_printing)) }
        viewModelScope.launch {
            val status = try {
                val bitmap = kiosk.ticketRenderer.render(QueueTicket.sample(), kiosk.ticketOptions())
                kiosk.printers.print(bitmap)
                str(R.string.setup_test_ok)
            } catch (e: PrinterException) {
                getApplication<Application>().getString(R.string.setup_test_failed, e.message.orEmpty())
            }
            _state.update { it.copy(busy = false, status = status) }
        }
    }

    fun continueToKiosk() {
        if (_state.value.selected != null) _events.trySend(Event.GoToKiosk)
    }

    private fun onFound(device: PrinterDevice) {
        _state.update { s ->
            if (s.devices.any { it.address == device.address }) s else s.copy(devices = s.devices + device)
        }
    }

    private fun str(@StringRes res: Int): String = getApplication<Application>().getString(res)

    private companion object {
        const val SCAN_TIMEOUT_MS = 20_000L
        const val AUTO_RECONNECT_INTERVAL_MS = 10_000L
    }
}
