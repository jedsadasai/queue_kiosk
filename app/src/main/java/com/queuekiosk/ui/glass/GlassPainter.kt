package com.queuekiosk.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.queuekiosk.R
import kotlin.math.min

/**
 * Paints one "liquid glass" shape for [view]:
 * 1. a soft drop shadow,
 * 2. inside the shape: the blurred background behind the view (from [GlassBackdropLayout]),
 *    a tinted gradient and a white sheen over the top half,
 * 3. a bright rim whose light runs from the top-left to the bottom-right, plus a faint dark hairline
 *    so white glass still shows on the white parts of the background.
 *
 * Without a [GlassBackdropLayout] ancestor (e.g. inside a dialog) a flat frost is used instead of the blur.
 * The owner view calls [onAttached], [onDetached] and [draw].
 */
class GlassPainter(private val view: View) {

    enum class Variant(@ColorRes val top: Int, @ColorRes val bottom: Int, val sheenAlpha: Int) {
        PRIMARY(R.color.glass_primary_top, R.color.glass_primary_bottom, 110),
        CLEAR(R.color.glass_clear_top, R.color.glass_clear_bottom, 150),
        CARD(R.color.glass_card_top, R.color.glass_card_bottom, 90),
        DANGER(R.color.glass_danger_top, R.color.glass_danger_bottom, 100);

        companion object {
            /** Matches the order of the `glassVariant` enum in attrs.xml. */
            fun fromAttr(value: Int): Variant = entries.getOrElse(value) { PRIMARY }
        }
    }

    var variant: Variant = Variant.PRIMARY
        set(value) { field = value; dirty = true; view.invalidate() }

    var pressed: Boolean = false
        set(value) { if (field != value) { field = value; dirty = true; view.invalidate() } }

    /** Corner radius in px; negative means a pill (half the height). */
    var cornerRadius: Float = -1f
        set(value) { field = value; dirty = true; view.invalidate() }

    internal val density = view.resources.displayMetrics.density
    private var insetLeft = 0f
    private var insetTop = 0f
    private var insetRight = 0f
    private var insetBottom = 0f

    private val rect = RectF()
    private val lastRect = RectF()
    private val path = Path()
    private val rimPath = Path()
    private var dirty = true

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        setShadowLayer(6 * density, 0f, 2 * density, 0x38000000)
    }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x1A000000
    }

    private var backdrop: GlassBackdropLayout? = null
    // The blur depends on where the view is on screen, so redraw when it may have moved.
    private val onScroll = ViewTreeObserver.OnScrollChangedListener { view.invalidate() }
    private val onLayout = ViewTreeObserver.OnGlobalLayoutListener { view.invalidate() }

    /** Space between the view's bounds and the glass shape, in px (room for the shadow). */
    fun setInsets(left: Float, top: Float, right: Float, bottom: Float) {
        insetLeft = left; insetTop = top; insetRight = right; insetBottom = bottom
        view.invalidate()
    }

    fun onAttached() {
        backdrop = view.findBackdrop()
        view.viewTreeObserver.addOnScrollChangedListener(onScroll)
        view.viewTreeObserver.addOnGlobalLayoutListener(onLayout)
    }

    fun onDetached() {
        view.viewTreeObserver.removeOnScrollChangedListener(onScroll)
        view.viewTreeObserver.removeOnGlobalLayoutListener(onLayout)
        backdrop = null
    }

    fun draw(canvas: Canvas) {
        rect.set(insetLeft, insetTop, view.width - insetRight, view.height - insetBottom)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        if (dirty || rect != lastRect) rebuild()

        // 1. Shadow, outside the shape only (the opaque shape itself is clipped away).
        canvas.save()
        canvas.clipOutPath(path)
        canvas.drawPath(path, shadowPaint)
        canvas.restore()

        // 2. The glass body.
        canvas.save()
        canvas.clipPath(path)
        backdrop?.drawBlurredBehind(canvas, view) ?: canvas.drawColor(FALLBACK_FROST)
        canvas.drawRect(rect, tintPaint)
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + rect.height() * SHEEN_HEIGHT, sheenPaint)
        canvas.restore()

        // 3. Edges.
        canvas.drawPath(path, hairlinePaint)
        canvas.drawPath(rimPath, rimPaint)
    }

    private fun rebuild() {
        dirty = false
        lastRect.set(rect)
        val maxRadius = min(rect.width(), rect.height()) / 2f
        val radius = if (cornerRadius < 0f) maxRadius else min(cornerRadius, maxRadius)

        path.rewind()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val half = rimPaint.strokeWidth / 2f
        val rimRect = RectF(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half)
        rimPath.rewind()
        rimPath.addRoundRect(rimRect, radius - half, radius - half, Path.Direction.CW)

        val context = view.context
        var top = ContextCompat.getColor(context, variant.top)
        var bottom = ContextCompat.getColor(context, variant.bottom)
        if (pressed) {
            top = pressedColor(top)
            bottom = pressedColor(bottom)
        }
        tintPaint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
        sheenPaint.shader = LinearGradient(
            0f, rect.top, 0f, rect.top + rect.height() * SHEEN_HEIGHT,
            Color.argb(if (pressed) variant.sheenAlpha / 2 else variant.sheenAlpha, 255, 255, 255),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        rimPaint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(Color.argb(240, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(150, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /** Pressed glass is a little darker and more solid. */
    private fun pressedColor(color: Int): Int {
        val darker = ColorUtils.blendARGB(color, Color.BLACK, 0.15f)
        return ColorUtils.setAlphaComponent(darker, (Color.alpha(color) + 40).coerceAtMost(255))
    }

    private companion object {
        const val SHEEN_HEIGHT = 0.55f
        const val FALLBACK_FROST = 0x99FFFFFF.toInt()
    }
}
