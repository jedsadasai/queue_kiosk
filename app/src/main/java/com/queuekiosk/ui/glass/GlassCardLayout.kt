package com.queuekiosk.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatTextView
import com.queuekiosk.R

/**
 * A frosted-glass panel (login form, printer list, chips). `app:glassVariant` defaults to `card`,
 * a mostly white glass that keeps forms readable; `app:glassCornerRadius` defaults to 20dp.
 * A shadow margin is added to the padding automatically.
 */
class GlassCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val glass = GlassPainter(this).also { it.configure(context, attrs, GlassPainter.Variant.CARD) }

    init {
        setWillNotDraw(false)
        val m = glass.shadowMargin()
        setPadding(paddingLeft + m, paddingTop + m, paddingRight + m, paddingBottom + m)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); glass.onAttached() }
    override fun onDetachedFromWindow() { glass.onDetached(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) = glass.draw(canvas)
}

/** A TextView on a glass chip, for banners whose visibility and text are set from code. */
class GlassTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    private val glass = GlassPainter(this).also { it.configure(context, attrs, GlassPainter.Variant.CLEAR) }

    init {
        val m = glass.shadowMargin()
        setPadding(paddingLeft + m, paddingTop + m, paddingRight + m, paddingBottom + m)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); glass.onAttached() }
    override fun onDetachedFromWindow() { glass.onDetached(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        glass.draw(canvas)
        super.onDraw(canvas)
    }
}

private const val SHADOW_MARGIN_DP = 6
private const val DEFAULT_RADIUS_DP = 20

private fun GlassPainter.shadowMargin(): Int = (SHADOW_MARGIN_DP * density).toInt()

private fun GlassPainter.configure(context: Context, attrs: AttributeSet?, default: GlassPainter.Variant) {
    val density = context.resources.displayMetrics.density
    val a = context.obtainStyledAttributes(attrs, R.styleable.GlassView)
    variant = if (a.hasValue(R.styleable.GlassView_glassVariant)) {
        GlassPainter.Variant.fromAttr(a.getInt(R.styleable.GlassView_glassVariant, 0))
    } else {
        default
    }
    cornerRadius = a.getDimension(R.styleable.GlassView_glassCornerRadius, DEFAULT_RADIUS_DP * density)
    a.recycle()
    val m = SHADOW_MARGIN_DP * density
    setInsets(m, m * 0.6f, m, m * 1.4f) // a little more room below, where the shadow falls
}
