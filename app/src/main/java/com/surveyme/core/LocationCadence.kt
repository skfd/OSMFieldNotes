package com.surveyme.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Battery pass (roadmap item 14): how often the tracking service asks for a fix depends on how
 * fast the walker moves. Pure Kotlin, no Android types, so the tier logic is unit-tested.
 *
 * Three tiers. Switching up (to a faster tier) happens on the first fix that says so; switching
 * down needs the slower speed to hold for at least [Tier.holdBeforeMillis] and two fixes, so a
 * red light does not drop a walker to the stationary tier and a GPS wobble does not lift a
 * standing walker to walking for one minute.
 *
 * The known cost of the stationary tier: the first fix after a long stand may be a minute late
 * and coarse (balanced power), so a note within the ping radius of the bench can be walked past
 * before the tier flips. Setting out is detected on the raw speed of that first fix, not on the
 * smoothed one, which is as early as it can be.
 */
object LocationCadence {

    enum class Tier(
        /** Requested interval between fixes. */
        val intervalMillis: Long,
        /** Fastest interval we accept when other apps ask for more. */
        val fastestIntervalMillis: Long,
        /** Fused location may drop fixes that moved less than this. */
        val minDisplacementMeters: Float,
        /** GPS (true) or balanced power (cell / wifi, occasional GPS). */
        val highAccuracy: Boolean,
        /** How long a slower speed must hold before we switch down into this tier. */
        val holdBeforeMillis: Long
    ) {
        STATIONARY(60_000L, 30_000L, 20f, false, 120_000L),
        WALKING(10_000L, 5_000L, 5f, true, 30_000L),
        FAST(5_000L, 2_000L, 0f, true, 0L)
    }

    const val STATIONARY_MAX_MPS = 0.5f
    const val WALKING_MAX_MPS = 2.5f

    /** Two fixes needed before a switch down, so one slow fix in a stride never counts. */
    const val FIXES_BEFORE_SWITCH_DOWN = 2

    /** Fixes closer in time than this carry no usable speed (a re-request echoes the last fix). */
    const val MIN_FIX_GAP_MILLIS = 1_000L

    /** Exponential smoothing weight of the newest speed sample. */
    private const val SPEED_ALPHA = 0.4f

    fun tierForSpeed(metersPerSecond: Float): Tier = when {
        metersPerSecond < STATIONARY_MAX_MPS -> Tier.STATIONARY
        metersPerSecond <= WALKING_MAX_MPS -> Tier.WALKING
        else -> Tier.FAST
    }

    /** A fix as the selector sees it. [speedMps] is the provider's own reading when it has one. */
    data class Fix(val latitude: Double, val longitude: Double, val timeMillis: Long, val speedMps: Float? = null)

    /**
     * Speed over ground between two fixes: the provider's reading when it has one, else distance
     * over time. Null when the fixes are too close in time to say anything.
     */
    fun speedBetween(previous: Fix?, current: Fix): Float? {
        if (previous == null) return current.speedMps?.takeIf { it >= 0f }
        val dt = current.timeMillis - previous.timeMillis
        if (dt < MIN_FIX_GAP_MILLIS) return null
        current.speedMps?.takeIf { it >= 0f }?.let { return it }
        return distanceMeters(previous.latitude, previous.longitude, current.latitude, current.longitude) / (dt / 1000f)
    }

    /** Haversine, metres. Own copy so tests need no Android `Location`. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return (2 * r * atan2(sqrt(a), sqrt(1 - a))).toFloat()
    }

    /**
     * Picks the tier fix by fix. Starts in [Tier.WALKING]: the walker pressed Start, so they
     * are probably about to move, and the first fixes must come quickly.
     */
    class TierSelector(initial: Tier = Tier.WALKING) {
        var tier: Tier = initial
            private set

        /** Smoothed speed, or null before the first usable sample. Reset on every tier change. */
        var smoothedMps: Float? = null
            private set

        private var previous: Fix? = null
        /** Wall-clock time of the last fix of any kind, for [onSilence]. */
        var lastFixMillis: Long? = null
            private set
        private var candidate: Tier? = null
        private var candidateSince = 0L
        private var candidateFixes = 0

        /**
         * Feeds one fix. Answers the new tier when it changed, null otherwise. Fixes that carry no
         * usable speed (see [speedBetween]) keep everything as it was, including the candidate.
         */
        fun onFix(fix: Fix): Tier? {
            val raw = speedBetween(previous, fix)
            previous = fix
            lastFixMillis = fix.timeMillis
            if (raw == null) return null

            val smoothed = smoothedMps?.let { it + SPEED_ALPHA * (raw - it) } ?: raw
            smoothedMps = smoothed

            // Up on the raw speed of a single fix: a smoother settled at zero after a long stand
            // would otherwise take several fixes to clear the threshold.
            val rawTier = tierForSpeed(raw)
            if (rawTier > tier) return switchTo(rawTier)

            val slowTier = tierForSpeed(smoothed)
            if (slowTier >= tier) {
                candidate = null
                return null
            }
            if (candidate != slowTier) {
                candidate = slowTier
                candidateSince = fix.timeMillis
                candidateFixes = 0
            }
            candidateFixes++
            val held = fix.timeMillis - candidateSince
            return if (candidateFixes >= FIXES_BEFORE_SWITCH_DOWN && held >= slowTier.holdBeforeMillis) {
                switchTo(slowTier)
            } else {
                null
            }
        }

        /**
         * The walking and fast tiers ask fused location to drop fixes that moved less than a few
         * metres, so a walker who stops gets no fixes at all while the GPS stays on. The service
         * calls this on a slow ticker: two minutes without any fix means stationary.
         */
        fun onSilence(nowMillis: Long): Tier? {
            val last = lastFixMillis ?: return null
            if (tier == Tier.STATIONARY) return null
            if (nowMillis - last < Tier.STATIONARY.holdBeforeMillis) return null
            return switchTo(Tier.STATIONARY)
        }

        private fun switchTo(next: Tier): Tier {
            tier = next
            candidate = null
            candidateFixes = 0
            smoothedMps = null
            return next
        }
    }

    /**
     * Whether a fix is worth a proximity pass. The pass walks every pingable POI; a fix that is
     * within [minMoveMeters] of the last one checked cannot change the answer unless enough time
     * passed for a cooldown to expire or a fetch to add rows. No previous check always passes.
     */
    fun shouldCheckProximity(
        movedMeters: Float?,
        sinceLastCheckMillis: Long?,
        minMoveMeters: Float = PROXIMITY_MIN_MOVE_METERS,
        maxSkipMillis: Long = PROXIMITY_MAX_SKIP_MILLIS
    ): Boolean {
        if (movedMeters == null || sinceLastCheckMillis == null) return true
        if (movedMeters >= minMoveMeters) return true
        return sinceLastCheckMillis >= maxSkipMillis
    }

    const val PROXIMITY_MIN_MOVE_METERS = 3f
    const val PROXIMITY_MAX_SKIP_MILLIS = 60_000L

    /**
     * A balanced-power fix can be a cell tower guess hundreds of metres out. Pinging on it would
     * mark the item notified and visited while the walker is nowhere near, so a fix that is less
     * accurate than twice the ping radius does not run the pass. No accuracy means trust it.
     */
    fun isFixUsableForPings(accuracyMeters: Float?, pingRadiusMeters: Int): Boolean =
        accuracyMeters == null || accuracyMeters <= pingRadiusMeters * 2f
}
