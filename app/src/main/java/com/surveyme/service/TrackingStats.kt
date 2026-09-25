package com.surveyme.service

import com.surveyme.core.LocationCadence

/**
 * Counters for one tracking session (battery pass, roadmap item 14). Reset when tracking
 * starts, frozen when it stops, shown in Settings and written to the log every ten minutes.
 */
data class TrackingStats(
    val active: Boolean = false,
    val startedAtMillis: Long = 0L,
    /** Fixes fused location delivered. */
    val fixes: Int = 0,
    /** Fixes that ran the proximity pass (the rest barely moved). */
    val proximityPasses: Int = 0,
    /** Fetch coroutines launched (notes and fixmes together count once). */
    val fetches: Int = 0,
    val pings: Int = 0,
    val tierChanges: Int = 0,
    val tier: LocationCadence.Tier = LocationCadence.Tier.WALKING,
    val batteryAtStart: Int? = null,
    val batteryNow: Int? = null,
    val updatedAtMillis: Long = 0L
) {
    val hasSession: Boolean get() = startedAtMillis > 0L

    /** One line, the same for the log and the Settings row. */
    fun describe(): String {
        if (!hasSession) return "no tracking session yet"
        val elapsed = (updatedAtMillis - startedAtMillis).coerceAtLeast(0L) / 60_000L
        val duration = if (elapsed >= 60) "${elapsed / 60}h ${elapsed % 60}m" else "${elapsed}m"
        val battery = when {
            batteryNow == null -> "battery ?"
            batteryAtStart == null -> "battery $batteryNow%"
            else -> "battery $batteryNow% (started $batteryAtStart%)"
        }
        val state = if (active) "tracking" else "stopped"
        return "$state $duration, $fixes fixes ($proximityPasses checked), $fetches fetches, $pings pings, " +
            "$tierChanges tier changes, ${tier.name.lowercase()}, $battery"
    }
}
