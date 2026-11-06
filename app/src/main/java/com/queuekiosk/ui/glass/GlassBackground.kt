package com.queuekiosk.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.queuekiosk.R

/**
 * The app background (`res/drawable-nodpi/bg_hospital.jpg`) and its pre-blurred copy, decoded once
 * and shared by every screen.
 *
 * The background never moves, so instead of blurring live (RenderEffect needs Android 12) each
 * glass view draws the matching part of [blurred]. That gives real frosted glass on Android 10+.
 */
object GlassBackground {

    /** The blurred copy is this many times smaller than the image, which also makes the blur cheap. */
    const val BLUR_DOWNSCALE = 4
    private const val BLUR_RADIUS = 6
    /** Liquid glass makes what is behind it a little more colourful. */
    private const val SATURATION = 1.3f

    /**
     * Bottom edge of the logo + hospital name at the top of the image, as a fraction of its
     * height. [BackdropHeaderSpace] keeps this band free. Update it if you swap the image.
     */
    const val LOGO_BAND_BOTTOM = 0.165f

    private class Images(val sharp: Bitmap, val blurred: Bitmap)

    @Volatile private var images: Images? = null

    fun sharp(context: Context): Bitmap = load(context).sharp
    fun blurred(context: Context): Bitmap = load(context).blurred

    private fun load(context: Context): Images =
        images ?: synchronized(this) { images ?: decode(context.applicationContext).also { images = it } }

    private fun decode(context: Context): Images {
        val sharp = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.bg_hospital,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )
        val w = (sharp.width / BLUR_DOWNSCALE).coerceAtLeast(1)
        val h = (sharp.height / BLUR_DOWNSCALE).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(sharp, w, h, true)

        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== sharp) small.recycle()
        val blurred = Bitmap.createBitmap(GlassBlur.blur(pixels, w, h, BLUR_RADIUS), w, h, Bitmap.Config.ARGB_8888)

        val vivid = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(SATURATION) })
        }
        Canvas(vivid).drawBitmap(blurred, 0f, 0f, paint)
        blurred.recycle()
        return Images(sharp, vivid)
    }
}
