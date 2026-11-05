package com.queuekiosk

import android.app.Application
import android.content.Context
import com.queuekiosk.data.AuthRepository
import com.queuekiosk.data.KioskPrefs
import com.queuekiosk.data.QueueRepository
import com.queuekiosk.data.api.ApiClientFactory
import com.queuekiosk.printer.PrinterManager
import com.queuekiosk.printer.PrinterRegistry
import com.queuekiosk.ticket.TicketRenderer
import kotlinx.coroutines.MainScope

/** Holds the app-wide singletons (a tiny service locator, so no DI framework is needed). */
class KioskApp : Application() {

    lateinit var prefs: KioskPrefs private set
    lateinit var auth: AuthRepository private set
    lateinit var queue: QueueRepository private set
    lateinit var printerRegistry: PrinterRegistry private set
    lateinit var printers: PrinterManager private set
    lateinit var ticketRenderer: TicketRenderer private set

    /** For work that must outlive a screen, e.g. disconnecting the printer in onStop. */
    val appScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        prefs = KioskPrefs(this)
        val api = ApiClientFactory(prefs)
        auth = AuthRepository(prefs, api)
        queue = QueueRepository(prefs, api, auth)
        printerRegistry = PrinterRegistry(this)
        printers = PrinterManager(printerRegistry, prefs)
        ticketRenderer = TicketRenderer(this)
    }

    fun ticketOptions() = TicketRenderer.Options(
        // Printed under the logo, as fleQ did (it hard-coded the name in the ticket layout).
        orgName = getString(R.string.ticket_org_name),
        showHistorySheet = prefs.showHistorySheet,
        showTotalQueue = prefs.showTotalQueue,
        showQrCode = prefs.showQrCode,
        paperWidthPx = prefs.paperWidthPx,
    )
}

val Context.kiosk: KioskApp get() = applicationContext as KioskApp
