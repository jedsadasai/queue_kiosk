package com.queuekiosk.printer.escpos

import java.io.ByteArrayOutputStream

/**
 * Turns an ARGB image into ESC/POS bytes. This is pure Kotlin with no Android types, so it is unit-testable.
 *
 * It does what fleQ asked the Epson SDK to do (addImage with HALFTONE_DITHER, then addFeedLine and addCut),
 * but with standard commands that almost every thermal printer understands:
 *
 *   ESC @            initialise
 *   ESC a 1          centre
 *   GS v 0 ...       raster bit image, sent in bands of at most [MAX_BAND_ROWS] rows
 *   ESC d n          feed n lines
 *   GS V 66 0        feed to cutter and partial cut (printers without a cutter ignore it)
 */
object EscPosEncoder {

    const val MAX_BAND_ROWS = 256

    private val INIT = byteArrayOf(0x1B, 0x40)
    private val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
    private val CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

    /**
     * @param argb   pixels row by row, `argb.size >= width * height`
     * @param dither Floyd–Steinberg dithering (good for logos/photos); false = plain threshold
     */
    fun encodeImage(
        argb: IntArray,
        width: Int,
        height: Int,
        feedLines: Int = 3,
        cut: Boolean = true,
        dither: Boolean = true,
    ): ByteArray {
        require(width > 0 && height > 0) { "Empty image ${width}x$height" }
        require(argb.size >= width * height) { "Pixel array too small" }
        require(feedLines in 0..255) { "feedLines must be 0..255" }

        val black = toMonochrome(argb, width, height, dither)
        return ByteArrayOutputStream().apply {
            write(INIT)
            write(ALIGN_CENTER)
            write(rasterBands(black, width, height))
            write(byteArrayOf(0x1B, 0x64, feedLines.toByte()))
            if (cut) write(CUT)
        }.toByteArray()
    }

    /** Perceived luminance 0 (black) .. 255 (white). Transparent pixels count as white paper. */
    fun luminance(argb: Int): Int {
        val a = argb ushr 24 and 0xFF
        val r = argb ushr 16 and 0xFF
        val g = argb ushr 8 and 0xFF
        val b = argb and 0xFF
        val lum = (r * 299 + g * 587 + b * 114) / 1000
        return (lum * a + 255 * (255 - a)) / 255
    }

    /** true = print a dot (black). */
    fun toMonochrome(argb: IntArray, width: Int, height: Int, dither: Boolean): BooleanArray {
        val lum = IntArray(width * height) { luminance(argb[it]) }
        val black = BooleanArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val old = lum[i].coerceIn(0, 255)
                val new = if (old < 128) 0 else 255
                black[i] = new == 0
                if (!dither) continue
                val err = old - new
                if (err == 0) continue
                if (x + 1 < width) lum[i + 1] += err * 7 / 16
                if (y + 1 < height) {
                    if (x > 0) lum[i + width - 1] += err * 3 / 16
                    lum[i + width] += err * 5 / 16
                    if (x + 1 < width) lum[i + width + 1] += err / 16
                }
            }
        }
        return black
    }

    /** `GS v 0 m xL xH yL yH d1...dk` per band, MSB = leftmost pixel, rows padded to whole bytes. */
    fun rasterBands(black: BooleanArray, width: Int, height: Int, bandRows: Int = MAX_BAND_ROWS): ByteArray {
        val bytesPerRow = (width + 7) / 8
        val out = ByteArrayOutputStream()
        var y0 = 0
        while (y0 < height) {
            val rows = minOf(bandRows, height - y0)
            out.write(0x1D); out.write(0x76); out.write(0x30); out.write(0x00)
            out.write(bytesPerRow and 0xFF); out.write(bytesPerRow shr 8 and 0xFF)
            out.write(rows and 0xFF); out.write(rows shr 8 and 0xFF)
            for (y in y0 until y0 + rows) {
                for (bx in 0 until bytesPerRow) {
                    var byte = 0
                    for (bit in 0 until 8) {
                        val x = bx * 8 + bit
                        if (x < width && black[y * width + x]) byte = byte or (0x80 ushr bit)
                    }
                    out.write(byte)
                }
            }
            y0 += rows
        }
        return out.toByteArray()
    }
}
