package com.surveyme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class QuietHoursTest {

    private fun m(h: Int, min: Int = 0) = h * 60 + min

    @Test
    fun `window within one day`() {
        val w = QuietHours.Window(m(13), m(15))
        assertFalse(w.wrapsMidnight)
        assertFalse(w.contains(m(12, 59)))
        assertTrue(w.contains(m(13)))
        assertTrue(w.contains(m(14, 30)))
        assertFalse(w.contains(m(15)))
        assertFalse(w.contains(m(23)))
    }

    @Test
    fun `default window wraps past midnight`() {
        val w = QuietHours.Window(PreferencesManager.DEFAULT_QUIET_START, PreferencesManager.DEFAULT_QUIET_END)
        assertTrue(w.wrapsMidnight)
        assertFalse(w.contains(m(21, 59)))
        assertTrue(w.contains(m(22)))
        assertTrue(w.contains(m(23, 59)))
        assertTrue(w.contains(0))
        assertTrue(w.contains(m(3)))
        assertTrue(w.contains(m(6, 59)))
        assertFalse(w.contains(m(7)))
        assertFalse(w.contains(m(12)))
    }

    @Test
    fun `equal start and end is the whole day`() {
        val w = QuietHours.Window(m(9), m(9))
        assertTrue(w.contains(0))
        assertTrue(w.contains(m(9)))
        assertTrue(w.contains(m(23, 59)))
    }

    @Test
    fun `out of range minutes are folded into the day`() {
        val w = QuietHours.Window(m(22), m(7))
        assertTrue(w.contains(m(23) + QuietHours.MINUTES_PER_DAY))
        assertTrue(w.contains(-60))
        assertFalse(w.contains(m(12) + QuietHours.MINUTES_PER_DAY))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bounds are checked`() {
        QuietHours.Window(m(24), 0)
    }

    @Test
    fun `no window is never quiet`() {
        assertFalse(QuietHours.isQuiet(null))
    }

    @Test
    fun `isQuiet reads the local clock`() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 30)
        val lateNight = cal.timeInMillis
        assertEquals(m(23, 30), QuietHours.minuteOfDay(lateNight))
        assertTrue(QuietHours.isQuiet(QuietHours.Window(m(22), m(7)), lateNight))
        assertFalse(QuietHours.isQuiet(QuietHours.Window(m(8), m(17)), lateNight))
        cal.set(Calendar.HOUR_OF_DAY, 6)
        cal.set(Calendar.MINUTE, 0)
        assertTrue(QuietHours.isQuiet(QuietHours.Window(m(22), m(7)), cal.timeInMillis))
    }

    @Test
    fun `formatting`() {
        assertEquals("07:05", QuietHours.format(m(7, 5)))
        assertEquals("22:00 – 07:00", QuietHours.Window(m(22), m(7)).describe())
    }
}
