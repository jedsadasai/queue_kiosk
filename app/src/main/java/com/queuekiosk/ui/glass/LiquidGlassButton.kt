package com.queuekiosk.ui.glass

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.queuekiosk.R

/**
 * A MaterialButton drawn as liquid glass (see [GlassPainter]). Use `app:glassVariant`
 * (primary | clear | danger) and, optionally, `app:cornerRadius` (default: pill).
 *
 * Material's own background is made transparent and only its ripple is kept.
 * Pressing shrinks the button slightly; a disabled button is drawn at half opacity.
 */
class LiquidGlassButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {

    private val glass = GlassPainter(this)

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.GlassView)
        val variant = GlassPainter.Variant.fromAttr(a.getInt(R.styleable.GlassView_glassVariant, 0))
        a.recycle()

        glass.variant = variant
        val density = resources.displayMetrics.density
        // Same vertical insets as Material's background, so the ripple lines up with the glass.
        glass.setInsets(2 * density, insetTop.toFloat(), 2 * density, insetBottom.toFloat())
        if (cornerRadius > 0) glass.cornerRadius = cornerRadius.toFloat()

        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        strokeWidth = 0
        stateListAnimator = null
        elevation = 0f
        rippleColor = ColorStateList.valueOf(0x33FFFFFF)
        if (variant == GlassPainter.Variant.CLEAR) {
            setTextColor(ContextCompat.getColor(context, R.color.brand))
        } else {
            setTextColor(Color.WHITE)
            setShadowLayer(4 * density, 0f, 1 * density, 0x40000000)
        }
    }

    override fun setCornerRadius(cornerRadius: Int) {
        super.setCornerRadius(cornerRadius)
        // Called from the superclass constructor, before `glass` exists.
        @Suppress("SENSELESS_COMPARISON")
        if (glass != null && cornerRadius > 0) glass.cornerRadius = cornerRadius.toFloat()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass.onAttached()
    }

    override fun onDetachedFromWindow() {
        glass.onDetached()
        super.onDetachedFromWindow()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        @Suppress("SENSELESS_COMPARISON")
        if (glass == null) return // called from the superclass constructor
        glass.pressed = isPressed
        val scale = if (isPressed) PRESSED_SCALE else 1f
        if (scaleX != scale) animate().scaleX(scale).scaleY(scale).setDuration(PRESS_ANIM_MS).start()
        alpha = if (isEnabled) 1f else DISABLED_ALPHA
    }

    // Glass first, then Material's (transparent) background with the ripple, then the text.
    override fun draw(canvas: Canvas) {
        glass.draw(canvas)
        super.draw(canvas)
    }

    private companion object {
        const val PRESSED_SCALE = 0.97f
        const val PRESS_ANIM_MS = 120L
        const val DISABLED_ALPHA = 0.5f
    }
}
