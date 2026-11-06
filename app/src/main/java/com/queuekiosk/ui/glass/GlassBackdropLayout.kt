package com.queuekiosk.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Root of every screen: draws the hospital background (top-anchored, filling the screen) and lets
 * the glass views inside it draw the blurred part of the background that is behind them.
 */
class GlassBackdropLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val sharp: Bitmap = GlassBackground.sharp(context)
    private val blurred: Bitmap = GlassBackground.blurred(context)
    private val imageMatrix = Matrix()
    private val blurMatrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var crop = GlassBlur.Crop(1f, 0f, 0f)
    private val myLocation = IntArray(2)
    private val childLocation = IntArray(2)

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Work out the crop before the children are measured: BackdropHeaderSpace needs it.
        updateCrop(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateCrop(w, h)
    }

    private fun updateCrop(w: Int, h: Int) {
        crop = GlassBlur.cropToFill(w, h, sharp.width, sharp.height)
        imageMatrix.setScale(crop.scale, crop.scale)
        imageMatrix.postTranslate(crop.dx, crop.dy)
        // The blurred copy is smaller, so scale it up to cover exactly the same area.
        blurMatrix.setScale(
            crop.scale * sharp.width / blurred.width,
            crop.scale * sharp.height / blurred.height,
        )
        blurMatrix.postTranslate(crop.dx, crop.dy)
        invalidate()
    }

    /** Y (in this layout) where the image's logo band ends. See [GlassBackground.LOGO_BAND_BOTTOM]. */
    fun logoBandBottom(): Int = (sharp.height * GlassBackground.LOGO_BAND_BOTTOM * crop.scale + crop.dy).toInt()

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(sharp, imageMatrix, paint)
    }

    /** Draws, into [child]'s canvas, the blurred background that lies behind [child]. */
    fun drawBlurredBehind(canvas: Canvas, child: View) {
        getLocationInWindow(myLocation)
        child.getLocationInWindow(childLocation)
        canvas.save()
        canvas.translate(
            (myLocation[0] - childLocation[0]).toFloat(),
            (myLocation[1] - childLocation[1]).toFloat(),
        )
        canvas.drawBitmap(blurred, blurMatrix, paint)
        canvas.restore()
    }
}
