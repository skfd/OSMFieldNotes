package com.surveyme.data

import org.robolectric.RuntimeEnvironment
import com.surveyme.core.Constants
import com.surveyme.core.FetchCity
import com.surveyme.core.PreferencesManager
import com.surveyme.data.NearbyFetcher.Outcome
import com.surveyme.data.NearbyFetcher.Trigger
import com.surveyme.data.model.Poi
import com.surveyme.data.repository.PoiRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Item 17: the refetch trigger. The real [NearbyFetcher] runs against real SharedPreferences
 * (Robolectric), a mocked repository and mocked services, with an injected clock, so what is
 * tested is the gate code itself: half-radius movement, staleness, the minimum interval, the
 * changed-radius reason (item 13), backoff doubling with Retry-After, and what MANUAL may skip.
 */
@RunWith(RobolectricTestRunner::class)
class NearbyFetcherTest {

    private companion object {
        // Toronto; 0,0 is the "no position yet" sentinel and must not be used as a centre.
        const val LAT = 43.70
        const val LON = -79.40
        /** Metres per degree of latitude, close enough for offsets of a few km. */
        const val M_PER_DEG_LAT = 111_320.0
        const val START = 1_790_000_000_000L
        const val SEC = 1000L
        const val MIN = 60 * SEC
    }

    private var now = START
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: PoiRepository
    private lateinit var notesService: OsmNotesService
    private lateinit var overpassService: OverpassService
    private lateinit var fetcher: NearbyFetcher

    @Before
    fun setUp() {
        prefs = PreferencesManager(RuntimeEnvironment.getApplication())
        repository = mockk(relaxed = true)
        notesService = mockk()
        overpassService = mockk()
        coEvery { notesService.fetchNotes(any(), any(), any()) } returns NotesPage(emptyList(), false)
        every { overpassService.fetchFixmePois(any(), any(), any()) } returns emptyList()
        coEvery { notesService.fetchNotesInBox(any(), any(), any(), any(), any()) } returns NotesPage(emptyList(), false)
        every { overpassService.fetchFixmePoisInRelation(any()) } returns emptyList()
        fetcher = NearbyFetcher(repository, prefs, notesService, overpassService, clock = { now })
    }

    private fun fetch(trigger: Trigger = Trigger.WALKER, lat: Double = LAT, lon: Double = LON) =
        runBlocking { fetcher.fetch(lat, lon, trigger) }

    private fun latAfterMoving(metres: Int) = LAT + metres / M_PER_DEG_LAT

    private fun assertBothFetched(report: NearbyFetcher.Report) {
        assertTrue(report.describe(), report.notes is Outcome.Fetched)
        assertTrue(report.describe(), report.fixmes is Outcome.Fetched)
    }

    private fun assertBothSkipped(report: NearbyFetcher.Report, reasonPart: String) {
        for (outcome in listOf(report.notes, report.fixmes)) {
            assertTrue(report.describe(), outcome is Outcome.Skipped)
            assertTrue(report.describe(), (outcome as Outcome.Skipped).reason.contains(reasonPart))
        }
    }

    @Test
    fun `a place never fetched wants a fetch and both sources fetch there`() {
        assertTrue(fetcher.wantsRefetch(LAT, LON))

        val report = fetch()

        assertBothFetched(report)
        assertTrue(report.touchedDatabase)
        coVerify(exactly = 1) { notesService.fetchNotes(LAT, LON, Constants.FETCH_RADIUS_METERS) }
        for (source in listOf(OsmNotesService.SOURCE_NOTES, OverpassService.SOURCE_FIXME)) {
            val centre = prefs.getFetchCentre(source)!!
            assertEquals(LAT, centre.latitude, 1e-9)
            assertEquals(LON, centre.longitude, 1e-9)
            assertEquals(Constants.FETCH_RADIUS_METERS, centre.radiusMeters)
            assertEquals(now, centre.timeMillis)
        }
    }

    @Test
    fun `the default map centre is never fetched`() {
        val report = fetch(lat = Constants.DEFAULT_MAP_CENTER_LAT, lon = Constants.DEFAULT_MAP_CENTER_LON)
        assertBothSkipped(report, "no position")
        assertFalse(fetcher.wantsRefetch(Constants.DEFAULT_MAP_CENTER_LAT, Constants.DEFAULT_MAP_CENTER_LON))
        coVerify(exactly = 0) { notesService.fetchNotes(any(), any(), any()) }
    }

    @Test
    fun `standing where the last fetch was is neither moved nor stale`() {
        fetch()
        now += 5 * MIN

        assertFalse(fetcher.wantsRefetch(LAT, LON))
        assertBothSkipped(fetch(), "not moved far enough")
        coVerify(exactly = 1) { notesService.fetchNotes(any(), any(), any()) }
    }

    @Test
    fun `moving under half the radius is not far enough`() {
        fetch()
        now += 5 * MIN

        val nearby = latAfterMoving(900)
        assertFalse(fetcher.wantsRefetch(nearby, LON))
        assertBothSkipped(fetch(lat = nearby), "not moved far enough")
    }

    @Test
    fun `moving past half the radius refetches once the minimum interval is over`() {
        fetch()
        val far = latAfterMoving(1100)

        now += 30 * SEC
        assertFalse("min interval still closes the gate", fetcher.wantsRefetch(far, LON))
        assertBothSkipped(fetch(lat = far), "min interval")

        now = START + Constants.FETCH_MIN_INTERVAL_MILLIS + SEC
        assertTrue(fetcher.wantsRefetch(far, LON))
        assertBothFetched(fetch(lat = far))
        assertEquals(far, prefs.getFetchCentre(OsmNotesService.SOURCE_NOTES)!!.latitude, 1e-9)
        coVerify(exactly = 2) { notesService.fetchNotes(any(), any(), any()) }
    }

    @Test
    fun `fifteen minutes without moving makes the last fetch stale`() {
        fetch()

        now = START + Constants.FETCH_STALE_MILLIS
        assertFalse(fetcher.wantsRefetch(LAT, LON))
        assertBothSkipped(fetch(), "not moved far enough")

        now = START + Constants.FETCH_STALE_MILLIS + SEC
        assertTrue(fetcher.wantsRefetch(LAT, LON))
        assertBothFetched(fetch())
    }

    @Test
    fun `a changed fetch radius is its own reason and the new radius is used`() {
        fetch()
        now += 5 * MIN
        assertFalse(fetcher.wantsRefetch(LAT, LON))

        prefs.fetchRadiusMeters = 5000
        assertTrue(fetcher.wantsRefetch(LAT, LON))
        assertBothFetched(fetch())
        coVerify(exactly = 1) { notesService.fetchNotes(LAT, LON, 5000) }
        assertEquals(5000, prefs.getFetchCentre(OsmNotesService.SOURCE_NOTES)!!.radiusMeters)

        // Once fetched with the new radius the reason is spent.
        now += 5 * MIN
        assertFalse(fetcher.wantsRefetch(LAT, LON))
    }

    @Test
    fun `manual skips the movement check and the minimum interval`() {
        fetch()
        now += 10 * SEC

        // The walker at the same spot is stopped by the movement check first; a walker who did
        // move is stopped by the minimum interval; the Settings button jumps both.
        assertBothSkipped(fetch(Trigger.WALKER), "not moved far enough")
        assertBothSkipped(fetch(Trigger.WALKER, lat = latAfterMoving(1500)), "min interval")
        assertBothFetched(fetch(Trigger.MANUAL))
        coVerify(exactly = 2) { notesService.fetchNotes(any(), any(), any()) }
    }

    @Test
    fun `map load follows the walker rules`() {
        fetch()
        now += 10 * SEC
        assertBothSkipped(fetch(Trigger.MAP_LOAD), "not moved far enough")
        assertBothSkipped(fetch(Trigger.MAP_LOAD, lat = latAfterMoving(1500)), "min interval")
        now += 20 * MIN
        assertBothFetched(fetch(Trigger.MAP_LOAD))
    }

    @Test
    fun `a failing source backs off, doubling each time, while the other source carries on`() {
        coEvery { notesService.fetchNotes(any(), any(), any()) } throws FetchHttpException(503)

        val first = fetch()
        assertTrue(first.notes is Outcome.Failed)
        assertEquals(Constants.FETCH_BACKOFF_BASE_MILLIS, (first.notes as Outcome.Failed).retryInMillis)
        assertTrue(first.fixmes is Outcome.Fetched)
        assertTrue("fixmes centre is saved, notes centre is not", prefs.getFetchCentre(OsmNotesService.SOURCE_NOTES) == null)

        // Inside the backoff the notes gate stays shut, even for a manual tap.
        now += Constants.FETCH_BACKOFF_BASE_MILLIS - SEC
        val during = fetch(Trigger.MANUAL)
        assertTrue(during.notes is Outcome.Skipped)
        assertTrue((during.notes as Outcome.Skipped).reason.contains("backing off"))
        assertTrue("manual still refetches the healthy source", during.fixmes is Outcome.Fetched)

        // A second failure after the backoff doubles it.
        now += 2 * SEC
        val second = fetch()
        assertEquals(2 * Constants.FETCH_BACKOFF_BASE_MILLIS, (second.notes as Outcome.Failed).retryInMillis)

        // A network error counts the same as an HTTP one.
        now += 2 * Constants.FETCH_BACKOFF_BASE_MILLIS + SEC
        coEvery { notesService.fetchNotes(any(), any(), any()) } throws java.io.IOException("unreachable")
        val third = fetch()
        assertEquals(4 * Constants.FETCH_BACKOFF_BASE_MILLIS, (third.notes as Outcome.Failed).retryInMillis)
        assertEquals("unreachable", (third.notes as Outcome.Failed).message)
    }

    @Test
    fun `Retry-After longer than the doubled backoff wins, capped at the maximum`() {
        coEvery { notesService.fetchNotes(any(), any(), any()) } throws FetchHttpException(429, retryAfterSeconds = 600)
        val first = fetch()
        assertEquals(600 * SEC, (first.notes as Outcome.Failed).retryInMillis)

        now += 600 * SEC + SEC
        coEvery { notesService.fetchNotes(any(), any(), any()) } throws FetchHttpException(429, retryAfterSeconds = 3600)
        val second = fetch()
        assertEquals(Constants.FETCH_BACKOFF_MAX_MILLIS, (second.notes as Outcome.Failed).retryInMillis)

        // A short Retry-After never shrinks the doubled backoff.
        now += Constants.FETCH_BACKOFF_MAX_MILLIS + SEC
        coEvery { notesService.fetchNotes(any(), any(), any()) } throws FetchHttpException(429, retryAfterSeconds = 5)
        val third = fetch()
        assertEquals(Constants.FETCH_BACKOFF_MAX_MILLIS, (third.notes as Outcome.Failed).retryInMillis)
    }

    @Test
    fun `a success resets the backoff so the next failure starts small again`() {
        coEvery { notesService.fetchNotes(any(), any(), any()) } throws FetchHttpException(500)
        fetch()
        now += Constants.FETCH_BACKOFF_BASE_MILLIS + SEC
        fetch()
        now += 2 * Constants.FETCH_BACKOFF_BASE_MILLIS + SEC

        coEvery { notesService.fetchNotes(any(), any(), any()) } returns NotesPage(emptyList(), false)
        assertTrue(fetch().notes is Outcome.Fetched)

        now += 20 * MIN
        coEvery { notesService.fetchNotes(any(), any(), any()) } throws FetchHttpException(500)
        assertEquals(Constants.FETCH_BACKOFF_BASE_MILLIS, (fetch().notes as Outcome.Failed).retryInMillis)
    }

    @Test
    fun `wantsRefetch is false while a wanted source is backing off`() {
        coEvery { notesService.fetchNotes(any(), any(), any()) } throws FetchHttpException(503)
        every { overpassService.fetchFixmePois(any(), any(), any()) } throws java.io.IOException("down")
        fetch()
        now += 30 * SEC
        assertFalse(fetcher.wantsRefetch(latAfterMoving(1500), LON))
        now += Constants.FETCH_BACKOFF_BASE_MILLIS
        assertTrue(fetcher.wantsRefetch(latAfterMoving(1500), LON))
    }

    @Test
    fun `only items inside the circle are stored, the square's corners are dropped`() {
        val inside = Poi(id = "in", name = "in", latitude = latAfterMoving(500), longitude = LON, source = OverpassService.SOURCE_FIXME)
        val corner = Poi(id = "corner", name = "corner", latitude = latAfterMoving(1900), longitude = LON - 1900 / (M_PER_DEG_LAT * 0.72), source = OverpassService.SOURCE_FIXME)
        every { overpassService.fetchFixmePois(any(), any(), any()) } returns listOf(inside, corner)
        coEvery { repository.expireOutside(any(), any()) } returns 3

        val report = fetch()

        assertEquals(Outcome.Fetched(fetched = 1, expired = 3), report.fixmes)
        coVerify { repository.insertPois(listOf(inside)) }
    }

    // Whole-city mode: Toronto's box as Nominatim gives it.
    private val toronto = FetchCity(324211L, "Toronto", south = 43.5796082, west = -79.6392832, north = 43.8554425, east = -79.1132193)

    @Test
    fun `a city fetches its box and relation even with no position yet`() {
        prefs.fetchCity = toronto
        assertTrue(fetcher.wantsRefetch(Constants.DEFAULT_MAP_CENTER_LAT, Constants.DEFAULT_MAP_CENTER_LON))

        assertBothFetched(fetch(lat = Constants.DEFAULT_MAP_CENTER_LAT, lon = Constants.DEFAULT_MAP_CENTER_LON))

        coVerify { notesService.fetchNotesInBox(toronto.west, toronto.south, toronto.east, toronto.north, any()) }
        coVerify { overpassService.fetchFixmePoisInRelation(324211L) }
        coVerify(exactly = 0) { notesService.fetchNotes(any(), any(), any()) }
        assertEquals("city:324211", prefs.getFetchCentre(OsmNotesService.SOURCE_NOTES)!!.areaKey)
    }

    @Test
    fun `in a city walking never refetches, an hour's staleness does`() {
        prefs.fetchCity = toronto
        fetch()
        now += 20 * MIN
        assertFalse("15 min staleness is for circles", fetcher.wantsRefetch(latAfterMoving(10_000), LON))
        assertBothSkipped(fetch(lat = latAfterMoving(10_000)), "city fetched under an hour ago")
        now += 41 * MIN
        assertTrue(fetcher.wantsRefetch(LAT, LON))
        assertBothFetched(fetch())
    }

    @Test
    fun `switching between radius and city, or between cities, is a refetch reason`() {
        fetch()
        now += 2 * MIN
        prefs.fetchCity = toronto
        assertTrue(fetcher.wantsRefetch(LAT, LON))
        assertBothFetched(fetch())
        now += 2 * MIN
        prefs.fetchCity = toronto.copy(relationId = 1L, name = "Elsewhere")
        assertBothFetched(fetch())
        now += 2 * MIN
        prefs.fetchCity = null
        assertBothFetched(fetch())
        assertEquals("r${prefs.fetchRadiusMeters}", prefs.getFetchCentre(OsmNotesService.SOURCE_NOTES)!!.areaKey)
    }

    @Test
    fun `a city keeps everything in its box, far from the walker, and nothing outside`() {
        prefs.fetchCity = toronto
        val farInCity = Poi(id = "east", name = "east", latitude = 43.78, longitude = -79.15, source = OverpassService.SOURCE_FIXME)
        val outside = Poi(id = "out", name = "out", latitude = 44.5, longitude = -79.4, source = OverpassService.SOURCE_FIXME)
        every { overpassService.fetchFixmePoisInRelation(any()) } returns listOf(farInCity, outside)
        val inArea = io.mockk.slot<(Poi) -> Boolean>()
        coEvery { repository.expireOutside(any(), capture(inArea)) } returns 0

        fetch(lat = 43.60, lon = -79.60)

        coVerify { repository.insertPois(listOf(farInCity)) }
        assertTrue(inArea.captured(farInCity))
        assertFalse(inArea.captured(outside))
    }

    @Test
    fun `a centre saved before area keys reads back as its radius key`() {
        prefs.saveFetchCentre(OsmNotesService.SOURCE_NOTES, PreferencesManager.FetchCentre(LAT, LON, 2000, START))
        assertEquals("r2000", prefs.getFetchCentre(OsmNotesService.SOURCE_NOTES)!!.areaKey)
    }
}
