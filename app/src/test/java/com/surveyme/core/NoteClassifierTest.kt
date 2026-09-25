package com.surveyme.core

import com.surveyme.data.model.NoteAction
import com.surveyme.data.model.NoteComment
import com.surveyme.data.model.NoteStatus
import com.surveyme.data.model.OsmNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Rule-level expectations ported from `analysis/classify_notes.py`; the corpus shares are in [NoteClassifierCorpusTest]. */
class NoteClassifierTest {

    private val classifier = NoteClassifier()
    private val today = utc("2026-09-23 12:00:00")

    private fun utc(s: String): Date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .parse(s)!!

    private fun note(
        text: String,
        author: String? = "mapper",
        created: String = "2026-09-20 10:00:00",
        replies: List<Pair<String?, String>> = emptyList(),
        lastReplyAt: String = created,
        id: Long = 1
    ): OsmNote {
        val createdAt = utc(created)
        val comments = listOf(NoteComment(createdAt, author, if (author != null) 1L else null, NoteAction.OPENED, text)) +
            replies.map { (who, body) -> NoteComment(utc(lastReplyAt), who, null, NoteAction.COMMENTED, body) }
        return OsmNote(noteId = id, poiId = OsmNote.poiIdFor(id), status = NoteStatus.OPEN, createdAt = createdAt, comments = comments)
    }

    private fun labels(note: OsmNote, me: String? = null) = classifier.classify(note, me, today).labels

    @Test
    fun `import follow-up asking for a survey is a surveyor address note`() {
        val l = labels(note("Please SURVEY addresses and entrances for this building. Imported from city data."))
        assertTrue(NoteLabels.SURVEY_REQUESTED in l)
        assertTrue(NoteLabels.ADDRESS in l)
        assertTrue(NoteLabels.IMPORT_FOLLOWUP in l)
        assertTrue(NoteLabels.SURVEYOR in l)
        assertTrue(NoteLabels.JOSM in l)
    }

    @Test
    fun `StreetComplete report stating an observation is a telemapper edit`() {
        val l = labels(note("Is there a playground here? Playground torn down\n\nvia StreetComplete 58.1:\nPlayground torn down"))
        assertTrue(NoteLabels.APP_STREETCOMPLETE in l)
        assertTrue(NoteLabels.APP_OBSERVED_NEEDS_EDIT in l)
        assertTrue(NoteLabels.TELEMAPPER in l)
        assertTrue(NoteLabels.PARKS in l)
        assertFalse(NoteLabels.LOW_SIGNAL in l)
    }

    @Test
    fun `StreetComplete report that asks a question needs a ground check`() {
        val l = labels(note("Something odd\n\nvia StreetComplete 58.1:\nNot sure if this is still here?"))
        assertTrue(NoteLabels.APP_NEEDS_GROUND_CHECK in l)
        assertTrue(NoteLabels.SURVEYOR in l)
        assertFalse(NoteLabels.APP_OBSERVED_NEEDS_EDIT in l)
    }

    @Test
    fun `Organic Maps place-does-not-exist report is a telemapper deletion, not low signal`() {
        val text = "\"Fabric Store\"\nA user of Organic Maps application has reported that the POI was visible on the map (see snapshot date below), but was not found on the ground.\nOSM snapshot date: 2026-03-01T12:00:00Z\nPOI name: Fabric Store\nPOI types: shop-fabric\n#organicmaps android 2026.04.10"
        val l = labels(note(text, author = null))
        assertTrue(NoteLabels.APP_ORGANICMAPS in l)
        assertTrue(NoteLabels.APP_OBSERVED_NEEDS_EDIT in l)
        assertTrue(NoteLabels.TELEMAPPER in l)
        assertTrue(NoteLabels.ANONYMOUS in l)
        assertFalse(NoteLabels.LOW_SIGNAL in l)
        // The snapshot date is boilerplate; it must not become a future-date hint.
        assertFalse(NoteLabels.FUTURE_DATED in l)
    }

    @Test
    fun `routing note with an element link is JOSM and telemapper-free`() {
        val l = labels(note("Turn restriction wrong at https://www.openstreetmap.org/way/12345, routing sends cars the wrong way."))
        assertTrue(NoteLabels.ROADS in l)
        assertTrue(NoteLabels.JOSM in l)
        assertTrue(NoteLabels.HAS_ELEMENT_LINK in l)
        assertFalse(NoteLabels.HAS_SOURCE_LINK in l)
        assertFalse(NoteLabels.TELEMAPPER in l)
    }

    @Test
    fun `city source link without a survey request is telemapper`() {
        val l = labels(note("New condo coming soon here, see https://www.toronto.ca/development/1234"))
        assertTrue(NoteLabels.HAS_SOURCE_LINK in l)
        assertTrue(NoteLabels.TELEMAPPER in l)
        assertTrue(NoteLabels.CONSTRUCTION in l)
        assertTrue(NoteLabels.FUTURE_DATED in l)
    }

    @Test
    fun `three words with no topic is low signal and unassigned`() {
        val l = labels(note("Very good city"))
        assertTrue(NoteLabels.LOW_SIGNAL in l)
        assertTrue(NoteLabels.AUDIENCES.none { it in l })
    }

    @Test
    fun `old untouched note is stale, a recently discussed one is not`() {
        val stale = labels(note("Is this shop still open?", created = "2023-01-01 10:00:00"))
        assertTrue(NoteLabels.STALE in stale)
        assertTrue(NoteLabels.BUSINESS in stale)
        assertTrue(NoteLabels.SURVEYOR in stale)

        val discussed = labels(
            note("Is this shop still open?", created = "2023-01-01 10:00:00", replies = listOf("other" to "Checked, still there."), lastReplyAt = "2026-06-01 10:00:00")
        )
        assertFalse(NoteLabels.STALE in discussed)
        assertTrue(NoteLabels.DISCUSSED in discussed)
    }

    @Test
    fun `parsed future date and a past date`() {
        assertTrue(NoteLabels.FUTURE_DATED in labels(note("Estimated completion March 2027.")))
        assertFalse(NoteLabels.FUTURE_DATED in labels(note("Was built in March 2019.")))
    }

    @Test
    fun `surveyme and resurvey strict tags force surveyor`() {
        val l = labels(note("Playground torn down\n\nvia StreetComplete 58.1:\nPlayground torn down", replies = listOf("me" to "#surveyme")))
        assertTrue(NoteLabels.TAG_SURVEYME in l)
        assertTrue(NoteLabels.SURVEYOR in l)
        assertTrue(NoteLabels.SURVEY_REQUESTED in l)
        assertFalse("strict tags replace the heuristic audience", NoteLabels.TELEMAPPER in l)

        assertTrue(NoteLabels.TAG_SURVEYME in labels(note("Check the bench.\n#RESURVEY")))
    }

    @Test
    fun `armchair and josm tags replace the heuristic audience`() {
        val armchair = labels(note("Please check if this is still here #armchair"))
        assertTrue(NoteLabels.TELEMAPPER in armchair)
        assertFalse(NoteLabels.SURVEYOR in armchair)
        assertTrue("states stay", NoteLabels.SURVEY_REQUESTED in armchair)

        val josm = labels(note("Is this shop still open? #josm"))
        assertEquals(setOf(NoteLabels.JOSM), josm.intersect(NoteLabels.AUDIENCES.toSet()))
    }

    @Test
    fun `surveyafter with a future date schedules the note`() {
        val result = classifier.classify(note("Check the new crossing. #surveyafter 2027-03"), null, today)
        assertEquals(utc("2027-03-01 00:00:00"), result.surveyAfter)
        assertTrue(NoteLabels.SCHEDULED in result.labels)
        assertTrue(NoteLabels.TAG_SURVEYAFTER in result.labels)
        assertTrue(NoteLabels.SURVEYOR in result.labels)
        assertFalse(NoteLabels.SURVEY_REQUESTED in result.labels)
    }

    @Test
    fun `surveyafter with a passed date behaves like surveyme`() {
        val result = classifier.classify(note("Check the new crossing. #surveyafter 2026-09-01"), null, today)
        assertEquals(utc("2026-09-01 00:00:00"), result.surveyAfter)
        assertFalse(NoteLabels.SCHEDULED in result.labels)
        assertTrue(NoteLabels.SURVEY_REQUESTED in result.labels)
        assertTrue(NoteLabels.SURVEYOR in result.labels)
    }

    @Test
    fun `surveyafter with an impossible month is ignored`() {
        val result = classifier.classify(note("#surveyafter 2027-13"), null, today)
        assertNull(result.surveyAfter)
        assertFalse(NoteLabels.TAG_SURVEYAFTER in result.labels)
    }

    @Test
    fun `mine and note to self`() {
        val n = note("Note to self: come back for the house numbers", author = "kk")
        assertTrue(NoteLabels.MINE in labels(n, me = "kk"))
        assertFalse(NoteLabels.MINE in labels(n, me = "someone"))
        assertFalse(NoteLabels.MINE in labels(n))
        assertTrue(NoteLabels.NOTE_TO_SELF in labels(n))
    }

    @Test
    fun `clusters need same author, same day, same opening words and proximity`() {
        val a = NoteClassifier.ClusterCandidate(note("What has HSBC become? Bank closed", id = 1), 43.700, -79.400)
        val b = NoteClassifier.ClusterCandidate(note("What has HSBC become now", id = 2), 43.701, -79.401)
        val far = NoteClassifier.ClusterCandidate(note("What has HSBC become there", id = 3), 43.900, -79.400)
        val otherDay = NoteClassifier.ClusterCandidate(note("What has HSBC become there", created = "2026-09-21 10:00:00", id = 4), 43.700, -79.400)
        val otherAuthor = NoteClassifier.ClusterCandidate(note("What has HSBC become there", author = "x", id = 5), 43.700, -79.400)
        val clustered = classifier.markClusters(listOf(a, b, far, otherDay, otherAuthor))
        assertEquals(setOf(1L, 2L), clustered)
    }
}
