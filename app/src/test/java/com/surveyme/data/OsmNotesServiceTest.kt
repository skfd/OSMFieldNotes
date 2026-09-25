package com.surveyme.data

import com.surveyme.data.model.NoteAction
import com.surveyme.data.model.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class OsmNotesServiceTest {

    private val service = OsmNotesService()

    // Shape of osm.org /api/0.6/notes.json: one open note with an anonymous reply, one closed.
    private val json = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-79.4153327,43.7787206]},
           "properties":{"id":5534047,"date_created":"2026-09-23 01:58:03 UTC","status":"open",
             "comments":[
               {"date":"2026-09-23 01:58:03 UTC","uid":12345,"user":"mapper","action":"opened","text":"Routing error.\n\nSecond paragraph."},
               {"date":"2026-09-24 10:00:00 UTC","action":"commented","text":"Anonymous reply"}
             ]}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-79.40,43.70]},
           "properties":{"id":99,"date_created":"2026-09-01 08:00:00 UTC","status":"closed","closed_at":"2026-09-20 12:30:00 UTC",
             "comments":[
               {"date":"2026-09-01 08:00:00 UTC","uid":1,"user":"a","action":"opened","text":"Is this shop open?"},
               {"date":"2026-09-20 12:30:00 UTC","uid":2,"user":"b","action":"closed","text":"Fixed"}
             ]}}
        ]}
    """.trimIndent()

    private fun utc(s: String) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!

    @Test
    fun `parses open and closed notes with structured comments`() {
        val page = service.parseNotesJson(json)

        assertFalse(page.truncated)
        assertEquals(2, page.notes.size)

        val open = page.notes[0].note
        assertEquals(5534047L, open.noteId)
        assertEquals("osm_note_5534047", open.poiId)
        assertEquals(NoteStatus.OPEN, open.status)
        assertEquals(utc("2026-09-23 01:58:03"), open.createdAt)
        assertNull(open.closedAt)
        assertEquals(2, open.comments.size)
        assertEquals("mapper", open.comments[0].author)
        assertEquals(12345L, open.comments[0].uid)
        assertEquals(NoteAction.OPENED, open.comments[0].action)
        assertNull(open.comments[1].author)
        assertNull(open.comments[1].uid)
        assertEquals(NoteAction.COMMENTED, open.comments[1].action)
        assertEquals("Anonymous reply", open.comments[1].text)
        assertTrue(open.labels.isEmpty())
        assertNull(open.surveyAfter)

        val closed = page.notes[1].note
        assertEquals(NoteStatus.CLOSED, closed.status)
        assertEquals(utc("2026-09-20 12:30:00"), closed.closedAt)
        assertEquals(NoteAction.CLOSED, closed.comments[1].action)
    }

    @Test
    fun `poi row carries the opening text and position`() {
        val poi = service.parseNotesJson(json).notes[0].poi
        assertEquals("osm_note_5534047", poi.id)
        assertEquals(OsmNotesService.SOURCE_NOTES, poi.source)
        assertEquals(43.7787206, poi.latitude, 1e-9)
        assertEquals(-79.4153327, poi.longitude, 1e-9)
        assertEquals("Routing error.\n\nSecond paragraph.", poi.description)
        assertEquals(utc("2026-09-23 01:58:03"), poi.createdAt)
        assertNotNull(poi.notes)
        assertTrue(poi.notes!!.contains("[mapper at "))
        assertTrue(poi.notes!!.contains("[Anonymous at "))
    }

    // Shape of the answer to POST notes/{id}/close.json: one Feature, the whole note after the change.
    private val closeAnswer = """
        {"type":"Feature","geometry":{"type":"Point","coordinates":[-79.40,43.70]},
         "properties":{"id":99,"url":"https://api.openstreetmap.org/api/0.6/notes/99.json",
           "reopen_url":"https://api.openstreetmap.org/api/0.6/notes/99/reopen.json",
           "date_created":"2026-09-01 08:00:00 UTC","status":"closed","closed_at":"2026-09-25 18:05:00 UTC",
           "comments":[
             {"date":"2026-09-01 08:00:00 UTC","uid":1,"user":"a","action":"opened","text":"Is this shop open?"},
             {"date":"2026-09-25 18:05:00 UTC","uid":7,"user":"me","action":"closed","text":"Checked, it is gone"}
           ]}}
    """.trimIndent()

    @Test
    fun `parses the single feature a post answers with`() {
        val fetched = service.parseNoteJson(closeAnswer)
        assertNotNull(fetched)
        val note = fetched!!.note
        assertEquals(99L, note.noteId)
        assertEquals(NoteStatus.CLOSED, note.status)
        assertEquals(utc("2026-09-25 18:05:00"), note.closedAt)
        assertEquals(2, note.comments.size)
        assertEquals("me", note.comments[1].author)
        assertEquals(NoteAction.CLOSED, note.comments[1].action)
        assertEquals("Checked, it is gone", note.comments[1].text)
        assertEquals("osm_note_99", fetched.poi.id)
        assertEquals(43.70, fetched.poi.latitude, 1e-9)
    }

    @Test
    fun `a non-note answer parses to null`() {
        assertNull(service.parseNoteJson("""{"type":"FeatureCollection","features":[]}"""))
        assertNull(service.parseNoteJson("not json"))
    }
}
