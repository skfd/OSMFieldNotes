package com.surveyme.core

import com.surveyme.data.model.Poi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Item 17: the proximity logic with a fake clock. Entering the radius pings once, the five
 * minute cooldown and the visited set hold it, and the radius is the item 13 setting for notes
 * and fixmes but the row's own for imported waypoints. The "barely moved" and "fix too coarse"
 * gates in front of the detector are [LocationCadence]'s and tested in [LocationCadenceTest].
 */
@RunWith(RobolectricTestRunner::class)
class ProximityDetectorTest {

    private companion object {
        const val LAT = 43.70
        const val LON = -79.40
        const val M_PER_DEG_LAT = 111_320.0
        /** Well past the cooldown: the first encounter compares against "last pinged at 0". */
        const val START = 1_790_000_000_000L
        const val COOLDOWN = 5 * 60 * 1000L
    }

    private var now = START
    private lateinit var detector: ProximityDetector

    @Before
    fun setUp() {
        now = START
        detector = ProximityDetector(clock = { now })
    }

    private fun poi(id: String, metresNorth: Int, source: String = "osm_notes", radius: Int = 50) = Poi(
        id = id,
        name = id,
        latitude = LAT + metresNorth / M_PER_DEG_LAT,
        longitude = LON,
        source = source,
        notificationRadius = radius
    )

    private fun pings(pois: List<Poi>, pingRadius: Int = 50): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        detector.checkProximity(LAT, LON, pois, pingRadius) { poi, distance -> out += poi.id to Math.round(distance) }
        return out
    }

    @Test
    fun `the offsets used here are honest metres`() {
        val d = detector.calculateDistance(LAT, LON, poi("x", 78).latitude, LON)
        assertEquals(78f, d, 1f)
    }

    @Test
    fun `entering the radius pings once with the distance`() {
        val near = poi("near", 30)
        val far = poi("far", 200)

        val first = pings(listOf(near, far))
        assertEquals(listOf("near" to 30), first)

        now += 10_000
        assertTrue("the same fix a moment later is silent", pings(listOf(near, far)).isEmpty())
    }

    @Test
    fun `the cooldown lets a POI ping again after five minutes`() {
        val near = poi("near", 30)
        pings(listOf(near))

        now = START + COOLDOWN
        assertTrue(pings(listOf(near)).isEmpty())

        now = START + COOLDOWN + 1
        assertEquals(listOf("near" to 30), pings(listOf(near)))

        now += 1
        assertTrue("the second ping restarts the cooldown", pings(listOf(near)).isEmpty())
    }

    @Test
    fun `a visited POI never pings until the visited set is reset`() {
        val near = poi("near", 30)
        detector.markPoiAsVisited("near")

        assertTrue(pings(listOf(near)).isEmpty())
        now += COOLDOWN + 1
        assertTrue(pings(listOf(near)).isEmpty())

        detector.resetVisitedPois()
        assertEquals(listOf("near" to 30), pings(listOf(near)))
    }

    @Test
    fun `reset also forgets the cooldowns`() {
        val near = poi("near", 30)
        pings(listOf(near))
        detector.resetVisitedPois()
        assertEquals(listOf("near" to 30), pings(listOf(near)))
    }

    @Test
    fun `notes and fixmes ping at the setting's radius, whatever their row says`() {
        val note = poi("note", 78, source = "osm_notes", radius = 500)
        val fixme = poi("fixme", 78, source = "osm_fixme", radius = 500)

        assertEquals(50, detector.radiusFor(note, 50))
        assertEquals(50, detector.radiusFor(fixme, 50))
        assertTrue(pings(listOf(note, fixme), pingRadius = 50).isEmpty())
        assertEquals(listOf("note" to 78, "fixme" to 78), pings(listOf(note, fixme), pingRadius = 100))
    }

    @Test
    fun `an imported waypoint keeps its own radius`() {
        val waypoint = poi("wp", 150, source = "gpx", radius = 200)
        val note = poi("note", 150)

        assertEquals(200, detector.radiusFor(waypoint, 50))
        assertEquals(listOf("wp" to 150), pings(listOf(waypoint, note), pingRadius = 50))

        val tight = poi("tight", 40, source = "gpx", radius = 25)
        assertTrue("a 25 m waypoint does not widen to the 200 m setting", pings(listOf(tight), pingRadius = 200).isEmpty())
    }

    @Test
    fun `each POI has its own cooldown`() {
        val a = poi("a", 10)
        val b = poi("b", 20)
        assertEquals(listOf("a" to 10), pings(listOf(a)))
        now += 60_000
        assertEquals(listOf("b" to 20), pings(listOf(a, b)))
    }

    @Test
    fun `the boundary is inclusive`() {
        val onEdge = poi("edge", 50)
        val d = detector.calculateDistance(LAT, LON, onEdge.latitude, onEdge.longitude)
        val radius = kotlin.math.ceil(d).toInt()
        assertEquals(listOf("edge" to Math.round(d)), pings(listOf(onEdge), pingRadius = radius))
        detector.resetVisitedPois()
        assertTrue(pings(listOf(onEdge), pingRadius = radius - 1).isEmpty())
    }

    @Test
    fun `getProximityPois lists what is within the distance, nearest first`() {
        val pois = listOf(poi("c", 400), poi("a", 100), poi("d", 600), poi("b", 250))
        val listed = detector.getProximityPois(LAT, LON, pois, maxDistance = 500f)
        assertEquals(listOf("a", "b", "c"), listed.map { it.first.id })
        assertTrue(listed.zipWithNext().all { (x, y) -> x.second <= y.second })
    }
}
