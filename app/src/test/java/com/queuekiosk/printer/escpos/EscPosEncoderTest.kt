package com.queuekiosk.printer.escpos

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscPosEncoderTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val transparent = 0x00000000

    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF

    @Test
    fun `8x1 black row produces init, centre, one raster band, feed and cut`() {
        val bytes = EscPosEncoder.encodeImage(IntArray(8) { black }, 8, 1, feedLines = 3, cut = true)

        val expected = byteArrayOf(
            0x1B, 0x40,                                     // ESC @
            0x1B, 0x61, 0x01,                               // ESC a 1
            0x1D, 0x76, 0x30, 0x00, 0x01, 0x00, 0x01, 0x00, // GS v 0, 1 byte wide, 1 row
            0xFF.toByte(),                                  // 8 black dots
            0x1B, 0x64, 0x03,                               // ESC d 3
            0x1D, 0x56, 0x42, 0x00,                         // GS V 66 0
        )
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `width is padded to whole bytes and MSB is the leftmost pixel`() {
        // 10 px wide: black, white x8, black  -> 1000_0000 0100_0000
        val row = IntArray(10) { white }.also { it[0] = black; it[9] = black }
        val raster = EscPosEncoder.rasterBands(EscPosEncoder.toMonochrome(row, 10, 1, dither = false), 10, 1)

        assertEquals(8 + 2, raster.size)
        assertEquals(2, raster.u(4)) // xL = 2 bytes per row
        assertEquals(0, raster.u(5)) // xH
        assertEquals(0x80, raster.u(8))
        assertEquals(0x40, raster.u(9))
    }

    @Test
    fun `tall images are split into bands of at most 256 rows`() {
        val width = 8
        val height = 300
        val raster = EscPosEncoder.rasterBands(BooleanArray(width * height), width, height)

        // band 1: header + 256 rows, band 2: header + 44 rows
        assertEquals(8 + 256 + 8 + 44, raster.size)
        assertEquals(0, raster.u(6)); assertEquals(1, raster.u(7))      // yL,yH = 256
        val second = 8 + 256
        assertEquals(0x1D, raster.u(second))
        assertEquals(44, raster.u(second + 6)); assertEquals(0, raster.u(second + 7))
    }

    @Test
    fun `transparent pixels are treated as white paper`() {
        assertEquals(255, EscPosEncoder.luminance(transparent))
        assertEquals(0, EscPosEncoder.luminance(black))
        val mono = EscPosEncoder.toMonochrome(intArrayOf(transparent, black), 2, 1, dither = true)
        assertFalse(mono[0])
        assertTrue(mono[1])
    }

    @Test
    fun `dithering a 50 percent grey prints roughly half the dots`() {
        val grey = 0xFF808080.toInt()
        val size = 32
        val mono = EscPosEncoder.toMonochrome(IntArray(size * size) { grey }, size, size, dither = true)
        val ratio = mono.count { it }.toDouble() / mono.size
        assertTrue("ratio was $ratio", ratio in 0.4..0.6)
    }

    @Test
    fun `no cut command when cut is false`() {
        val bytes = EscPosEncoder.encodeImage(IntArray(8) { white }, 8, 1, feedLines = 2, cut = false)
        assertArrayEquals(byteArrayOf(0x1B, 0x64, 0x02), bytes.copyOfRange(bytes.size - 3, bytes.size))
    }
}
