package com.surveyme.core

import com.surveyme.data.OsmNotesService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Runs the Kotlin port over the 305 open Toronto notes of 2026-09-23 (`analysis/toronto_open_notes.json`,
 * copied to test resources) and checks it lands where `analysis/classify_notes.py` did:
 * surveyor 59%, telemapper 33%, JOSM 30%, plus a few structural counts that catch date and
 * lowercasing regressions the audience shares would not.
 */
@RunWith(RobolectricTestRunner::class)
class NoteClassifierCorpusTest {

    private val today = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .parse("2026-09-23 12:00:00")!!

    /** Label set per note, clusters included. */
    private fun classifyCorpus(): List<Set<String>> {
        val json = javaClass.classLoader!!.getResource("toronto_open_notes.json")!!.readText()
        val page = OsmNotesService().parseNotesJson(json, now = today)
        assertEquals(305, page.notes.size)

        val classifier = NoteClassifier()
        val labelled = page.notes.map { f ->
            val result = classifier.classify(f.note, ownDisplayName = null, today = today)
            NoteClassifier.ClusterCandidate(f.note.copy(labels = result.labels, surveyAfter = result.surveyAfter), f.poi.latitude, f.poi.longitude)
        }
        val clustered = classifier.markClusters(labelled)
        return labelled.map { c ->
            if (c.note.noteId in clustered) c.note.labels + NoteLabels.DUPLICATE_CLUSTER else c.note.labels
        }
    }

    private fun load(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        classifyCorpus().forEach { labels -> labels.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        println("Corpus label counts: " + counts.toSortedMap())
        return counts
    }

    private fun assertShare(counts: Map<String, Int>, label: String, expectedPercent: Int, tolerance: Int = 8) {
        val percent = 100 * (counts[label] ?: 0) / 305
        assertTrue("$label: $percent% (expected $expectedPercent% ± $tolerance)", kotlin.math.abs(percent - expectedPercent) <= tolerance)
    }

    private fun assertCount(counts: Map<String, Int>, label: String, expected: Int, tolerance: Int = 3) {
        val actual = counts[label] ?: 0
        assertTrue("$label: $actual (expected $expected ± $tolerance)", kotlin.math.abs(actual - expected) <= tolerance)
    }

    @Test
    fun `audience shares match the prototype within 8 points`() {
        val counts = load()
        assertShare(counts, NoteLabels.SURVEYOR, 59)
        assertShare(counts, NoteLabels.TELEMAPPER, 33)
        assertShare(counts, NoteLabels.JOSM, 30)
    }

    @Test
    fun `structural counts match the prototype`() {
        val counts = load()
        assertCount(counts, NoteLabels.ANONYMOUS, 37)
        assertCount(counts, NoteLabels.DISCUSSED, 87)
        assertCount(counts, NoteLabels.APP_STREETCOMPLETE, 39)
        assertCount(counts, NoteLabels.STALE, 33)
        assertCount(counts, NoteLabels.SURVEY_REQUESTED, 101)
        assertCount(counts, NoteLabels.HAS_ELEMENT_LINK, 38)
        assertCount(counts, NoteLabels.HAS_SOURCE_LINK, 34)
        assertCount(counts, NoteLabels.LOW_SIGNAL, 10)
        assertCount(counts, NoteLabels.DUPLICATE_CLUSTER, 21)
        assertCount(counts, NoteLabels.TAG_SURVEYME, 5, tolerance = 1)
    }

    @Test
    fun `Organic Maps and CoMaps together match the prototype's combined count`() {
        // The prototype had one app_organicmaps label for both apps: 23 notes.
        val either = classifyCorpus().count { NoteLabels.APP_ORGANICMAPS in it || NoteLabels.APP_COMAPS in it }
        assertTrue("organic/comaps: $either (expected 23 ± 3)", kotlin.math.abs(either - 23) <= 3)
    }
}
