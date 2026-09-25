package com.surveyme.core

import com.surveyme.data.model.NoteStatus
import com.surveyme.data.model.OsmNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class NoteTriageTest {

    private val t0 = 1_800_000_000_000L
    private val hour = 3600 * 1000L

    private fun note(
        id: Long,
        createdAt: Long,
        status: NoteStatus = NoteStatus.OPEN,
        labels: Set<String> = emptySet()
    ) = OsmNote(noteId = id, poiId = OsmNote.poiIdFor(id), status = status, createdAt = Date(createdAt), labels = labels)

    @Test
    fun `never triaged shows every open note, newest first`() {
        val notes = listOf(note(1, t0), note(2, t0 + hour), note(3, t0 - hour))
        assertEquals(listOf(2L, 1L, 3L), NoteTriage.select(notes, NoteTriage.NEVER).map { it.noteId })
    }

    @Test
    fun `only notes created after the stamp are new`() {
        val notes = listOf(note(1, t0 - hour), note(2, t0), note(3, t0 + hour))
        assertEquals(listOf(3L), NoteTriage.select(notes, lastTriageAt = t0).map { it.noteId })
    }

    @Test
    fun `show all ignores the stamp but still sorts newest first`() {
        val notes = listOf(note(1, t0 - hour), note(2, t0 + hour))
        assertEquals(listOf(2L, 1L), NoteTriage.select(notes, lastTriageAt = t0 + 2 * hour, showAll = true).map { it.noteId })
    }

    @Test
    fun `closed notes, placeholders and skipped ids are left out`() {
        val notes = listOf(
            note(1, t0 + hour),
            note(2, t0 + hour, status = NoteStatus.CLOSED),
            note(-3, t0 + hour),
            note(4, t0 + hour)
        )
        assertEquals(listOf(1L), NoteTriage.select(notes, NoteTriage.NEVER, skipped = setOf(4L)).map { it.noteId })
    }

    @Test
    fun `guess follows the heuristic audience`() {
        assertEquals(NoteTriage.TAG_SURVEYME, NoteTriage.guessFor(setOf(NoteLabels.SURVEYOR)))
        assertEquals(NoteTriage.TAG_SURVEYAFTER, NoteTriage.guessFor(setOf(NoteLabels.SURVEYOR, NoteLabels.FUTURE_DATED)))
        assertEquals(NoteTriage.TAG_ARMCHAIR, NoteTriage.guessFor(setOf(NoteLabels.TELEMAPPER)))
        assertEquals(NoteTriage.TAG_JOSM, NoteTriage.guessFor(setOf(NoteLabels.JOSM)))
        assertNull(NoteTriage.guessFor(setOf(NoteLabels.ADDRESS)))
    }

    @Test
    fun `strict tags already on the note are recognised`() {
        assertEquals(NoteTriage.TAG_ARMCHAIR, NoteTriage.strictTagOf(setOf(NoteLabels.TAG_ARMCHAIR, NoteLabels.TELEMAPPER)))
        assertNull(NoteTriage.strictTagOf(setOf(NoteLabels.SURVEYOR)))
    }

    @Test
    fun `survey-after comment carries the ISO day in UTC`() {
        // 2027-03-01T00:00Z as MaterialDatePicker answers it.
        assertEquals("#surveyafter 2027-03-01", NoteTriage.commentFor(NoteTriage.TAG_SURVEYAFTER, Date(1803859200000L)))
        assertEquals("#josm", NoteTriage.commentFor(NoteTriage.TAG_JOSM))
    }
}
