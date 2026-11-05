package com.queuekiosk.ticket

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isVisible
import com.queuekiosk.R
import com.queuekiosk.data.KioskPrefs
import com.queuekiosk.data.QueueTicket
import com.queuekiosk.databinding.ViewTicketBinding
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Renders the queue ticket layout to a bitmap exactly as wide as the paper, ready for any
 * [com.queuekiosk.printer.PrinterDriver].
 *
 * view_ticket.xml reproduces fleQ's view_queue_card_for_c_building.xml (logo + hospital name,
 * number with the building as a superscript, OPD/ER history sheet, date | time, footer).
 * fleQ grabbed that view with the deprecated drawing cache and scaled it to 550 px. Here the
 * layout is measured directly at the printer width with a fixed density, so it prints the same
 * on any tablet: view_ticket.xml is designed [DESIGN_WIDTH_DP] dp wide, and 1 dp = 1 printer dot
 * on 80 mm paper.
 */
class TicketRenderer(context: Context) {

    private val appContext = context.applicationContext

    data class Options(
        val orgName: String,
        val showHistorySheet: Boolean,
        val showTotalQueue: Boolean,
        /** fleQ built the QR from url_qr but never printed it, hence off by default. */
        val showQrCode: Boolean = false,
        val paperWidthPx: Int = KioskPrefs.PAPER_80MM_PX,
    )

    fun render(ticket: QueueTicket, options: Options): Bitmap {
        val ctx = printContext(options.paperWidthPx)
        val b = ViewTicketBinding.inflate(LayoutInflater.from(ctx))

        b.ticketOrgName.text = options.orgName
        b.ticketNumber.text = ticket.number
        b.ticketBuilding.setTextOrHide(ticket.building)

        val qr = ticket.urlQr?.takeIf { options.showQrCode && it.isNotBlank() }
        b.ticketQr.isVisible = qr != null
        if (qr != null) {
            val sizePx = (QR_SIZE_DP * ctx.resources.displayMetrics.density).roundToInt()
            b.ticketQr.setImageBitmap(QrCode.encode(qr, sizePx))
        }

        b.ticketHistory.isVisible = options.showHistorySheet
        if (options.showHistorySheet) addHistoryRows(ctx, b.ticketHistory)

        val total = ticket.total?.takeIf { it.isNotBlank() }
        b.ticketTotal.isVisible = options.showTotalQueue && total != null
        if (total != null) b.ticketTotal.text = ctx.getString(R.string.ticket_waiting_count, total)

        b.ticketDateTime.text = ctx.getString(
            R.string.ticket_date_time,
            SimpleDateFormat("dd-MM-yyyy", THAI).format(ticket.issuedAt),
            SimpleDateFormat("HH:mm", THAI).format(ticket.issuedAt),
        )

        return drawToBitmap(b.root, options.paperWidthPx)
    }

    /** A context whose density maps [DESIGN_WIDTH_DP] dp onto [paperWidthPx] pixels. */
    private fun printContext(paperWidthPx: Int): Context {
        val config = Configuration(appContext.resources.configuration).apply {
            densityDpi = (DisplayMetrics.DENSITY_DEFAULT * paperWidthPx / DESIGN_WIDTH_DP.toFloat()).roundToInt()
            fontScale = 1f
        }
        return ContextThemeWrapper(appContext.createConfigurationContext(config), R.style.Theme_QueueKiosk)
    }

    /**
     * fleQ's optional OPD/ER "history sheet": label, a dotted line to write on, optional suffix.
     * Items of R.array.ticket_history_fields are "label" or "label|suffix"; "" is a blank line.
     */
    private fun addHistoryRows(ctx: Context, container: LinearLayout) {
        val inflater = LayoutInflater.from(ctx)
        ctx.resources.getStringArray(R.array.ticket_history_fields).forEach { item ->
            val label = item.substringBefore('|').trim()
            val suffix = item.substringAfter('|', "").trim()
            val row = inflater.inflate(R.layout.view_ticket_history_row, container, false)
            row.findViewById<TextView>(R.id.historyLabel).setTextOrHide(label)
            row.findViewById<TextView>(R.id.historySuffix).setTextOrHide(suffix)
            container.addView(row)
        }
    }

    private fun drawToBitmap(view: View, widthPx: Int): Bitmap {
        if (view.layoutParams == null) {
            view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            view.draw(this)
        }
        return bitmap
    }

    private fun TextView.setTextOrHide(value: String?) {
        isVisible = !value.isNullOrBlank()
        text = value.orEmpty()
    }

    companion object {
        const val DESIGN_WIDTH_DP = 576
        private const val QR_SIZE_DP = 200
        private val THAI = Locale.forLanguageTag("th-TH")
    }
}
