package com.surveyme.core

import java.util.Calendar

/**
 * The quiet-hours window (roadmap item 13): pings are suppressed inside it, fetches carry on.
 * Pure, in minutes after local midnight, so the wrap past midnight is unit-testable without
 * a clock or a zone. `java.time` is avoided on purpose: minSdk 23 without desugaring.
 */
object QuietHours {
    const val MINUTES_PER_DAY = 24 * 60

    /**
     * [start] and [end] are minutes after midnight, 0..1439. The window is [start, end) and
     * wraps when [end] is before [start]: 22:00 to 07:00 covers 22:00 through 06:59. Equal
     * values mean the whole day (the user set a window that never ends), the only reading
     * under which "on" still does something.
     */
    data class Window(val start: Int, val end: Int) {
        init {
            require(start in 0 until MINUTES_PER_DAY) { "start out of range: $start" }
            require(end in 0 until MINUTES_PER_DAY) { "end out of range: $end" }
        }

        /** True when [minuteOfDay] falls inside the window. */
        fun contains(minuteOfDay: Int): Boolean {
            val m = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
            return when {
                start == end -> true
                start < end -> m >= start && m < end
                else -> m >= start || m < end
            }
        }

        val wrapsMidnight: Boolean get() = end <= start

        /** "22:00 – 07:00". */
        fun describe(): String = "${format(start)} – ${format(end)}"
    }

    /** "07:05". */
    fun format(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    /** The local minute of the day for [timeMillis], in the default zone. */
    fun minuteOfDay(timeMillis: Long = System.currentTimeMillis()): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    /** True when [window] is set and contains the local time now. */
    fun isQuiet(window: Window?, timeMillis: Long = System.currentTimeMillis()): Boolean =
        window != null && window.contains(minuteOfDay(timeMillis))
}
