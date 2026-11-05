package com.queuekiosk.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingHoursTest {

    @Test
    fun `daytime window is open-inclusive and close-exclusive`() {
        assertFalse(WorkingHours.isOpen(6, openHour = 7, closeHour = 16))
        assertTrue(WorkingHours.isOpen(7, openHour = 7, closeHour = 16))
        assertTrue(WorkingHours.isOpen(15, openHour = 7, closeHour = 16))
        assertFalse(WorkingHours.isOpen(16, openHour = 7, closeHour = 16))
    }

    @Test
    fun `0 to 24 is open all day`() {
        (0..23).forEach { assertTrue(WorkingHours.isOpen(it, openHour = 0, closeHour = 24)) }
    }

    @Test
    fun `window spanning midnight`() {
        assertTrue(WorkingHours.isOpen(23, openHour = 22, closeHour = 6))
        assertTrue(WorkingHours.isOpen(2, openHour = 22, closeHour = 6))
        assertFalse(WorkingHours.isOpen(12, openHour = 22, closeHour = 6))
    }

    @Test
    fun `equal open and close means always open`() {
        assertTrue(WorkingHours.isOpen(3, openHour = 8, closeHour = 8))
    }
}
