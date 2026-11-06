package com.queuekiosk.ui.glass

import kotlin.math.max
import kotlin.math.roundToInt

/** Pure maths behind the glass effect, kept free of Android classes so it can be unit-tested. */
object GlassBlur {

    /**
     * Blurs ARGB [pixels] with [passes] separable box blurs of [radius], which approximates a
     * Gaussian blur. Edge pixels are repeated. Returns a new array; the input is not changed.
     */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int = 3): IntArray {
        require(pixels.size == width * height) { "pixels.size ${pixels.size} != $width x $height" }
        if (radius <= 0 || width == 0 || height == 0) return pixels.copyOf()
        var src = pixels.copyOf()
        var dst = IntArray(pixels.size)
        repeat(passes) {
            boxPass(src, dst, width, height, radius, horizontal = true)
            boxPass(dst, src, width, height, radius, horizontal = false)
        }
        return src
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val window = 2 * r + 1
        for (line in 0 until lines) {
            fun index(i: Int): Int {
                val c = i.coerceIn(0, length - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            var a = 0; var red = 0; var green = 0; var blue = 0
            for (j in -r..r) {
                val p = src[index(j)]
                a += p ushr 24; red += (p shr 16) and 0xFF; green += (p shr 8) and 0xFF; blue += p and 0xFF
            }
            for (i in 0 until length) {
                dst[index(i)] = (avg(a, window) shl 24) or (avg(red, window) shl 16) or
                    (avg(green, window) shl 8) or avg(blue, window)
                val out = src[index(i - r)]
                val inc = src[index(i + r + 1)]
                a += (inc ushr 24) - (out ushr 24)
                red += ((inc shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                green += ((inc shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                blue += (inc and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun avg(sum: Int, count: Int) = ((sum + count / 2) / count).coerceIn(0, 255)

    /** How an image is scaled and placed to fill a view: `x' = x * scale + dx`, `y' = y * scale + dy`. */
    data class Crop(val scale: Float, val dx: Float, val dy: Float)

    /**
     * Fills a [viewW] x [viewH] view with a [imgW] x [imgH] image, like centerCrop, except that the
     * crop is anchored to the **top** so the logo at the top of the background is never cut off.
     */
    fun cropToFill(viewW: Int, viewH: Int, imgW: Int, imgH: Int): Crop {
        if (viewW <= 0 || viewH <= 0 || imgW <= 0 || imgH <= 0) return Crop(1f, 0f, 0f)
        val scale = max(viewW.toFloat() / imgW, viewH.toFloat() / imgH)
        val dx = ((viewW - imgW * scale) / 2f).roundToInt().toFloat()
        return Crop(scale, dx, 0f)
    }
}
