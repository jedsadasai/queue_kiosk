package com.queuekiosk.data

import java.util.Calendar

/**
 * fleQ gated the "take queue" button with RajtheneeAlarmManager.checkIfWorkingTime(), driven by
 * alarms. This is the same rule as a pure function.
 *
 * Open from [openHour]:00 (inclusive) until [closeHour]:00 (exclusive), with closeHour in 1..24.
 * If openHour > closeHour the window spans midnight. If they are equal, the kiosk is always open.
 */
object WorkingHours {

    fun isOpen(hourOfDay: Int, openHour: Int, closeHour: Int): Boolean {
        require(hourOfDay in 0..23) { "hourOfDay must be 0..23, was $hourOfDay" }
        return when {
            openHour == closeHour -> true
            openHour < closeHour -> hourOfDay in openHour until closeHour
            else -> hourOfDay >= openHour || hourOfDay < closeHour
        }
    }

    fun isOpen(now: Calendar, openHour: Int, closeHour: Int): Boolean =
        isOpen(now.get(Calendar.HOUR_OF_DAY), openHour, closeHour)
}
