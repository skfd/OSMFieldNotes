package com.surveyme.core

import com.surveyme.data.model.NoteStatus
import com.surveyme.data.model.OsmNote
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
}
