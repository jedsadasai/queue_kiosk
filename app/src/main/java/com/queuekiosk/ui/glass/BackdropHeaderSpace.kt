package com.queuekiosk.ui.glass

import android.content.Context
import android.util.AttributeSet
import android.view.View

/**
 * Empty space as tall as the logo band at the top of the background image, so content starts
 * below the image's logo on every screen size and orientation.
 *
 * Put it at the top of the screen, inside a [GlassBackdropLayout].
 */
class BackdropHeaderSpace @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val backdrop = findBackdrop()
        // The backdrop's padding is the status bar: the logo band is measured from the screen top.
        val band = backdrop?.let { it.logoBandBottom() - it.paddingTop } ?: 0
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            band.coerceAtLeast(suggestedMinimumHeight),
        )
    }
}

internal fun View.findBackdrop(): GlassBackdropLayout? {
    var p = parent
    while (p != null) {
        if (p is GlassBackdropLayout) return p
        p = p.parent
    }
    return null
}
