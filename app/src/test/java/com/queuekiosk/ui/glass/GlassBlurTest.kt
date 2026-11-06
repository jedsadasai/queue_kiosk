package com.queuekiosk.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassBlurTest {

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun red(p: Int) = (p shr 16) and 0xFF

    @Test
    fun uniformImageStaysUniform() {
        val px = IntArray(20 * 10) { gray(120) }
        val out = GlassBlur.blur(px, 20, 10, radius = 3)
        assertEquals(px.size, out.size)
        out.forEach { assertEquals(gray(120), it) }
    }

    @Test
    fun brightPixelSpreadsSymmetricallyAndKeepsItsTotal() {
        val w = 41
        val h = 41
        val px = IntArray(w * h) { gray(0) }
        px[20 * w + 20] = gray(255)
        val out = GlassBlur.blur(px, w, h, radius = 2)

        val centre = red(out[20 * w + 20])
        assertTrue("centre dimmed", centre in 1 until 255)
        assertEquals(red(out[20 * w + 17]), red(out[20 * w + 23]))
        assertEquals(red(out[17 * w + 20]), red(out[23 * w + 20]))
        assertEquals(red(out[20 * w + 17]), red(out[17 * w + 20]))
        // Rounding per pass loses or adds a little, but the light is not created or lost.
        val total = out.sumOf { red(it) }
        assertTrue("total $total", total in 200..320)
        // Alpha stays opaque.
        out.forEach { assertEquals(0xFF, it ushr 24) }
    }

    @Test
    fun inputIsNotModified() {
        val px = IntArray(9) { gray(it * 20) }
        val copy = px.copyOf()
        GlassBlur.blur(px, 3, 3, radius = 1)
        assertTrue(px.contentEquals(copy))
    }

    @Test
    fun portraitScreenIsCroppedFromTheTop() {
        // 683x1024 image on a 1200x1920 portrait tablet: height decides, sides are trimmed evenly.
        val c = GlassBlur.cropToFill(1200, 1920, 683, 1024)
        assertEquals(1920f / 1024, c.scale, 1e-4f)
        assertEquals(0f, c.dy)
        assertEquals(Math.round((1200 - 683 * c.scale) / 2).toFloat(), c.dx)
        assertTrue(c.dx < 0)
    }

    @Test
    fun landscapeScreenKeepsTheTopAndCropsTheBottom() {
        val c = GlassBlur.cropToFill(1920, 1200, 683, 1024)
        assertEquals(1920f / 683, c.scale, 1e-4f)
        assertEquals(0f, c.dx)
        assertEquals(0f, c.dy)
        assertTrue(1024 * c.scale > 1200)
    }
}
