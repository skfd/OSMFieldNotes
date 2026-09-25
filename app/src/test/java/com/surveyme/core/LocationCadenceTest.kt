package com.surveyme.core

import com.surveyme.core.LocationCadence.Fix
import com.surveyme.core.LocationCadence.Tier
import com.surveyme.core.LocationCadence.TierSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCadenceTest {

    /** Latitude of the last fed fix; each test starts afresh south of Toronto's city hall. */
    private var lat = 43.65

    /** Fixes every [stepMillis], moving north at [mps] from the last fed position; speed comes from the provider. */
    private fun feed(
        selector: TierSelector,
        count: Int,
        mps: Float,
        startMillis: Long,
        stepMillis: Long = 10_000L,
        reported: Boolean = true
    ): List<Tier?> {
        val changes = mutableListOf<Tier?>()
        for (i in 0 until count) {
            val t = startMillis + i * stepMillis
            lat += (mps * stepMillis / 1000.0) / 111_320.0
            changes += selector.onFix(Fix(lat, -79.38, t, if (reported) mps else null))
        }
        return changes
    }

    @Test
    fun `thresholds split the three tiers`() {
        assertEquals(Tier.STATIONARY, LocationCadence.tierForSpeed(0f))
        assertEquals(Tier.STATIONARY, LocationCadence.tierForSpeed(0.49f))
        assertEquals(Tier.WALKING, LocationCadence.tierForSpeed(0.5f))
        assertEquals(Tier.WALKING, LocationCadence.tierForSpeed(1.4f))
        assertEquals(Tier.WALKING, LocationCadence.tierForSpeed(2.5f))
        assertEquals(Tier.FAST, LocationCadence.tierForSpeed(2.51f))
        assertEquals(Tier.FAST, LocationCadence.tierForSpeed(8f))
    }

    @Test
    fun `starts walking and switches up to fast on the first fast fix`() {
        val s = TierSelector()
        assertEquals(Tier.WALKING, s.tier)
        assertNull(s.onFix(Fix(43.65, -79.38, 0L, 1.2f)))
        assertEquals(Tier.FAST, s.onFix(Fix(43.65, -79.38, 10_000L, 6f)))
        assertEquals(Tier.FAST, s.tier)
    }

    @Test
    fun `one slow fix does not switch down`() {
        val s = TierSelector()
        s.onFix(Fix(43.65, -79.38, 0L, 6f))
        assertEquals(Tier.FAST, s.tier)
        assertNull(s.onFix(Fix(43.65, -79.38, 10_000L, 1.0f)))
        assertEquals(Tier.FAST, s.tier)
    }

    @Test
    fun `fast drops to walking after two fixes and thirty seconds`() {
        val s = TierSelector()
        s.onFix(Fix(43.65, -79.38, 0L, 6f))
        val changes = feed(s, 5, 1.2f, startMillis = 10_000L, stepMillis = 10_000L)
        // Candidate starts at t=10s; needs >=2 fixes and >=30s held: t=40s is the fourth fix.
        assertEquals(listOf(null, null, null, Tier.WALKING, null), changes)
        assertEquals(Tier.WALKING, s.tier)
    }

    @Test
    fun `walking drops to stationary only after two minutes`() {
        val s = TierSelector()
        val changes = feed(s, 14, 0.1f, startMillis = 0L, stepMillis = 10_000L)
        // Candidate since t=0; held >= 120s first at t=120s, the 13th fix.
        val firstChange = changes.indexOfFirst { it != null }
        assertEquals(12, firstChange)
        assertEquals(Tier.STATIONARY, changes[firstChange])
        assertTrue(changes.drop(firstChange + 1).all { it == null })
    }

    @Test
    fun `a short walk in the middle restarts the stationary clock`() {
        val s = TierSelector()
        feed(s, 6, 0.1f, startMillis = 0L)                       // 0..50 s slow
        assertTrue(feed(s, 3, 1.4f, startMillis = 60_000L).all { it == null })  // 60..80 s walking
        val changes = feed(s, 10, 0.1f, startMillis = 90_000L)   // 90..180 s slow
        // The smoothed speed clears 0.5 m/s at 90 s, so the clock restarts after that; the
        // earliest switch is past 200 s, so none within 180 s.
        assertTrue(changes.all { it == null })
        assertEquals(Tier.WALKING, s.tier)
    }

    @Test
    fun `two minutes without any fix means stationary`() {
        val s = TierSelector()
        assertNull(s.onSilence(500_000L))                       // nothing seen yet
        feed(s, 2, 1.4f, startMillis = 0L)
        assertEquals(Tier.WALKING, s.tier)
        assertNull(s.onSilence(10_000L + 119_000L))
        assertEquals(Tier.STATIONARY, s.onSilence(10_000L + 120_000L))
        assertNull(s.onSilence(10_000L + 300_000L))             // already there
        // Setting out again lifts it on the first fix.
        assertEquals(Tier.WALKING, s.onFix(Fix(lat + 0.001, -79.38, 400_000L, 1.3f)))
    }

    @Test
    fun `stationary for three minutes then one walking fix switches up at once`() {
        val s = TierSelector()
        feed(s, 19, 0.05f, startMillis = 0L)     // 0..180 s
        assertEquals(Tier.STATIONARY, s.tier)
        assertEquals(Tier.WALKING, s.onFix(Fix(43.6502, -79.38, 240_000L, 1.4f)))
    }

    @Test
    fun `speed is derived from distance when the provider has none`() {
        val s = TierSelector()
        // ~1.3 m/s northwards, no reported speed: stays walking.
        feed(s, 4, 1.3f, startMillis = 0L, reported = false)
        assertEquals(Tier.WALKING, s.tier)
        val smoothed = s.smoothedMps
        assertNotNull(smoothed)
        assertTrue("smoothed $smoothed", smoothed!! in 1.1f..1.5f)
        // ~4 m/s derived: up to fast on the first such fix.
        val last = feed(s, 1, 4f, startMillis = 40_000L, reported = false)
        assertEquals(Tier.FAST, last.single())
    }

    @Test
    fun `an echoed fix with no time gap carries no speed and changes nothing`() {
        val s = TierSelector()
        s.onFix(Fix(43.65, -79.38, 0L, 6f))
        assertEquals(Tier.FAST, s.tier)
        assertNull(LocationCadence.speedBetween(Fix(43.65, -79.38, 0L), Fix(43.66, -79.38, 0L)))
        assertNull(s.onFix(Fix(43.66, -79.38, 0L, 0f)))
        assertNull(s.onFix(Fix(43.66, -79.38, 500L, 0f)))
        assertEquals(Tier.FAST, s.tier)
    }

    @Test
    fun `negative provider speed is ignored`() {
        assertNull(LocationCadence.speedBetween(null, Fix(43.65, -79.38, 0L, -1f)))
    }

    @Test
    fun `haversine is close to the real thing`() {
        // One degree of latitude is about 111.2 km.
        val d = LocationCadence.distanceMeters(43.0, -79.0, 44.0, -79.0)
        assertTrue("$d", d in 111_000f..111_400f)
    }

    @Test
    fun `proximity pass runs on the first fix and after a tier change`() {
        assertTrue(LocationCadence.shouldCheckProximity(null, null))
        assertTrue(LocationCadence.shouldCheckProximity(1f, null))
        assertTrue(LocationCadence.shouldCheckProximity(null, 1_000L))
    }

    @Test
    fun `proximity pass is skipped when barely moved and recently checked`() {
        assertFalse(LocationCadence.shouldCheckProximity(0f, 5_000L))
        assertFalse(LocationCadence.shouldCheckProximity(2.9f, 59_000L))
        assertTrue(LocationCadence.shouldCheckProximity(3f, 5_000L))
        assertTrue(LocationCadence.shouldCheckProximity(0f, 60_000L))
    }

    @Test
    fun `a derived move inside the accuracy circle is standing still, not walking`() {
        // 35 m north in 60 s is 0.58 m/s, enough for walking, if it were real.
        val a = Fix(43.65, -79.38, 0L, null, 50f)
        val b = Fix(43.65 + 35.0 / 111_320.0, -79.38, 60_000L, null, 50f)
        assertEquals(0f, LocationCadence.speedBetween(a, b))
        val s = TierSelector(Tier.STATIONARY)
        assertNull(s.onFix(a))
        assertNull(s.onFix(b))
        assertEquals(Tier.STATIONARY, s.tier)
        // The same jump with a 10 m GPS fix is a real move.
        val s2 = TierSelector(Tier.STATIONARY)
        assertNull(s2.onFix(a.copy(accuracyMeters = 10f)))
        assertEquals(Tier.WALKING, s2.onFix(b.copy(accuracyMeters = 10f)))
    }

    @Test
    fun `a coarse fix does not run the proximity pass`() {
        assertTrue(LocationCadence.isFixUsableForPings(null, 50))
        assertTrue(LocationCadence.isFixUsableForPings(100f, 50))
        assertFalse(LocationCadence.isFixUsableForPings(101f, 50))
        assertTrue(LocationCadence.isFixUsableForPings(300f, 200))
    }
}
