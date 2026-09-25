package com.surveyme.core

import com.surveyme.data.model.NoteStatus
import com.surveyme.data.model.OsmNote
import com.surveyme.data.model.Poi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class NotePingPolicyTest {

    private val now = Date(1_800_000_000_000L)

    private fun note(
        labels: Set<String> = setOf(NoteLabels.SURVEYOR),
        status: NoteStatus = NoteStatus.OPEN,
        surveyAfter: Date? = null,
        id: Long = 1
    ) = OsmNote(noteId = id, poiId = OsmNote.poiIdFor(id), status = status, createdAt = now, labels = labels, surveyAfter = surveyAfter)

    @Test
    fun `fixmes and pre-v4 rows have no note and keep pinging`() {
        assertTrue(NotePingPolicy.mayPing(null, now))
    }

    @Test
    fun `open surveyor note pings`() {
        assertTrue(NotePingPolicy.mayPing(note(), now))
    }

    @Test
    fun `closed, pending, non-surveyor and stale notes do not ping`() {
        assertFalse(NotePingPolicy.mayPing(note(status = NoteStatus.CLOSED), now))
        assertFalse(NotePingPolicy.mayPing(note(id = -5), now))
        assertFalse(NotePingPolicy.mayPing(note(labels = setOf(NoteLabels.TELEMAPPER, NoteLabels.JOSM)), now))
        assertFalse(NotePingPolicy.mayPing(note(labels = emptySet()), now))
        assertFalse(NotePingPolicy.mayPing(note(labels = setOf(NoteLabels.SURVEYOR, NoteLabels.STALE)), now))
    }

    @Test
    fun `scheduled note pings only once its date has come, whatever its stored label says`() {
        val future = Date(now.time + 24 * 3600 * 1000L)
        val past = Date(now.time - 24 * 3600 * 1000L)
        assertFalse(NotePingPolicy.mayPing(note(surveyAfter = future), now))
        assertTrue(NotePingPolicy.mayPing(note(labels = setOf(NoteLabels.SURVEYOR, NoteLabels.SCHEDULED), surveyAfter = past), now))
    }

    @Test
    fun `future_dated is a hint only and does not hide`() {
        assertTrue(NotePingPolicy.mayPing(note(labels = setOf(NoteLabels.SURVEYOR, NoteLabels.FUTURE_DATED)), now))
    }

    private fun fixme(
        muted: Boolean = false,
        type: String? = "node",
        lastEditedAt: Date? = Date(now.time - 30L * 24 * 3600 * 1000)
    ) = Poi(
        id = "overpass_${type}_1", name = "FIXME Issue", latitude = 0.0, longitude = 0.0, source = "osm_fixme",
        isMuted = muted, osmElementType = type, osmElementId = 1, lastEditedAt = lastEditedAt
    )

    @Test
    fun `fixme pings unless dismissed, on a relation, or too old for the setting`() {
        assertTrue(NotePingPolicy.mayPing(fixme(), null, now, FixmeMaxAge.ONE_YEAR))
        assertFalse(NotePingPolicy.mayPing(fixme(muted = true), null, now, FixmeMaxAge.ANY))
        assertFalse(NotePingPolicy.mayPing(fixme(type = "relation"), null, now, FixmeMaxAge.ANY))
        val old = fixme(lastEditedAt = Date(now.time - 3L * 365 * 24 * 3600 * 1000))
        assertFalse(NotePingPolicy.mayPing(old, null, now, FixmeMaxAge.TWO_YEARS))
        assertTrue(NotePingPolicy.mayPing(old, null, now, FixmeMaxAge.FIVE_YEARS))
        assertTrue(NotePingPolicy.mayPing(old, null, now, FixmeMaxAge.ANY))
        // A pre-v9 row has no date yet; it passes until the next fetch fills one in.
        assertTrue(NotePingPolicy.mayPing(fixme(lastEditedAt = null), null, now, FixmeMaxAge.ONE_YEAR))
    }

    @Test
    fun `relation detected from the id string of a pre-v7 row`() {
        val legacy = Poi(id = "overpass_relation_9", name = "FIXME Issue", latitude = 0.0, longitude = 0.0, source = "osm_fixme")
        assertTrue(legacy.isOnRelation)
        assertFalse(NotePingPolicy.mayPing(legacy, null, now))
    }

    // Item 13: the class set, the mute switches.

    @Test
    fun `class set decides which audiences ping`() {
        val tele = note(labels = setOf(NoteLabels.TELEMAPPER))
        val josm = note(labels = setOf(NoteLabels.JOSM))
        val both = note(labels = setOf(NoteLabels.SURVEYOR, NoteLabels.TELEMAPPER))
        assertFalse(NotePingPolicy.mayPing(tele, now))
        assertTrue(NotePingPolicy.mayPing(tele, now, setOf(NoteLabels.TELEMAPPER)))
        assertTrue(NotePingPolicy.mayPing(josm, now, setOf(NoteLabels.JOSM, NoteLabels.SURVEYOR)))
        assertFalse(NotePingPolicy.mayPing(josm, now, setOf(NoteLabels.TELEMAPPER)))
        // A note in several classes pings when any of them is ticked.
        assertTrue(NotePingPolicy.mayPing(both, now, setOf(NoteLabels.TELEMAPPER)))
        assertTrue(NotePingPolicy.mayPing(both, now, setOf(NoteLabels.SURVEYOR)))
        // Nothing ticked: no note pings.
        assertFalse(NotePingPolicy.mayPing(note(), now, emptySet()))
    }

    @Test
    fun `unclassified is its own tick`() {
        val plain = note(labels = setOf(NoteLabels.ADDRESS))
        assertFalse(NotePingPolicy.mayPing(plain, now))
        assertFalse(NotePingPolicy.mayPing(plain, now, NoteLabels.AUDIENCES.toSet()))
        assertTrue(NotePingPolicy.mayPing(plain, now, setOf(PingSettings.UNCLASSIFIED)))
        // Ticking "unclassified" does not let a classified note through.
        assertFalse(NotePingPolicy.mayPing(note(labels = setOf(NoteLabels.JOSM)), now, setOf(PingSettings.UNCLASSIFIED)))
    }

    @Test
    fun `stale and scheduled rules are not settings`() {
        val stale = note(labels = setOf(NoteLabels.TELEMAPPER, NoteLabels.STALE))
        assertFalse(NotePingPolicy.mayPing(stale, now, PingSettings.ALL_CLASSES.toSet()))
        val future = note(labels = setOf(NoteLabels.JOSM), surveyAfter = Date(now.time + 3600_000L))
        assertFalse(NotePingPolicy.mayPing(future, now, PingSettings.ALL_CLASSES.toSet()))
    }

    @Test
    fun `mute switches gate their kind and nothing else`() {
        val notePoi = Poi(id = OsmNote.poiIdFor(1), name = "Note", latitude = 0.0, longitude = 0.0, source = "osm_notes")
        val waypoint = Poi(id = "w", name = "Waypoint", latitude = 0.0, longitude = 0.0, source = "gpx_import")
        val muteNotes = PingSettings(muteNotes = true)
        val muteFixmes = PingSettings(muteFixmes = true)

        assertFalse(NotePingPolicy.mayPing(notePoi, note(), now, muteNotes))
        assertTrue(NotePingPolicy.mayPing(fixme(), null, now, muteNotes))
        assertTrue(NotePingPolicy.mayPing(waypoint, null, now, muteNotes))
        // A note POI without its note row (pre-v4 data) is still a note.
        assertFalse(NotePingPolicy.mayPing(notePoi, null, now, muteNotes))

        assertFalse(NotePingPolicy.mayPing(fixme(), null, now, muteFixmes))
        assertTrue(NotePingPolicy.mayPing(notePoi, note(), now, muteFixmes))
        assertTrue(NotePingPolicy.mayPing(waypoint, null, now, muteFixmes))
    }

    @Test
    fun `settings carry the class set and the fixme age`() {
        val notePoi = Poi(id = OsmNote.poiIdFor(1), name = "Note", latitude = 0.0, longitude = 0.0, source = "osm_notes")
        val settings = PingSettings(classes = setOf(NoteLabels.JOSM), fixmeMaxAge = FixmeMaxAge.ONE_YEAR)
        assertTrue(NotePingPolicy.mayPing(notePoi, note(labels = setOf(NoteLabels.JOSM)), now, settings))
        assertFalse(NotePingPolicy.mayPing(notePoi, note(), now, settings))
        val old = fixme(lastEditedAt = Date(now.time - 400L * 24 * 3600 * 1000))
        assertFalse(NotePingPolicy.mayPing(old, null, now, settings))
        assertTrue(NotePingPolicy.mayPing(fixme(), null, now, settings))
    }

    @Test
    fun `waypoints and notes go through the poi overload unchanged`() {
        val waypoint = Poi(id = "w", name = "Waypoint", latitude = 0.0, longitude = 0.0, source = "gpx_import")
        assertTrue(NotePingPolicy.mayPing(waypoint, null, now, FixmeMaxAge.ONE_YEAR))
        val notePoi = Poi(id = OsmNote.poiIdFor(1), name = "Note", latitude = 0.0, longitude = 0.0, source = "osm_notes")
        assertTrue(NotePingPolicy.mayPing(notePoi, note(), now))
        assertFalse(NotePingPolicy.mayPing(notePoi, note(status = NoteStatus.CLOSED), now))
    }
}
