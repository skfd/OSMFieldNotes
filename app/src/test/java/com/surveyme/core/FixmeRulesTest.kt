package com.surveyme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class FixmeRulesTest {

    private fun labels(text: String?) = FixmeClassifier.classify(text)

    @Test
    fun `continue phrases`() {
        assertEquals(setOf(FixmeLabels.CONTINUE), labels("continue"))
        assertEquals(setOf(FixmeLabels.CONTINUE), labels("Incomplete, more to map here"))
        assertEquals(setOf(FixmeLabels.CONTINUE), labels("unfinished path"))
    }

    @Test
    fun `resurvey phrases`() {
        assertEquals(setOf(FixmeLabels.RESURVEY), labels("resurvey"))
        assertEquals(setOf(FixmeLabels.RESURVEY), labels("please survey opening hours"))
        assertEquals(setOf(FixmeLabels.RESURVEY), labels("Check if still open"))
        assertEquals(setOf(FixmeLabels.RESURVEY), labels("confirm this exists"))
    }

    @Test
    fun `check name, position and tags phrases`() {
        assertEquals(setOf(FixmeLabels.CHECK_NAME), labels("What is this called?"))
        assertEquals(setOf(FixmeLabels.CHECK_NAME), labels("spelling?"))
        assertEquals(setOf(FixmeLabels.CHECK_POSITION), labels("approximate location, align with imagery"))
        assertEquals(setOf(FixmeLabels.CHECK_POSITION), labels("position is a guess"))
        assertEquals(setOf(FixmeLabels.CHECK_TAGS), labels("tagging is probably wrong"))
        assertEquals(setOf(FixmeLabels.CHECK_TAGS), labels("which type of shop is this"))
    }

    @Test
    fun `one phrase can carry two labels`() {
        assertEquals(setOf(FixmeLabels.RESURVEY, FixmeLabels.CHECK_NAME), labels("verify name"))
        assertEquals(setOf(FixmeLabels.RESURVEY, FixmeLabels.CHECK_POSITION), labels("check position on site"))
    }

    @Test
    fun `whole words only, case-insensitive, and nothing for silence`() {
        // "checked", "moved", "typed" and "named" are not requests.
        assertEquals(emptySet<String>(), labels("checked 2019, moved the typed thing named foo"))
        assertEquals(setOf(FixmeLabels.RESURVEY), labels("RESURVEY"))
        assertEquals(emptySet<String>(), labels("yes"))
        assertEquals(emptySet<String>(), labels(null))
        assertEquals(emptySet<String>(), labels("   "))
    }

    @Test
    fun `labels order and display names`() {
        assertEquals(
            listOf(FixmeLabels.CONTINUE, FixmeLabels.CHECK_TAGS, "zzz"),
            FixmeLabels.ordered(setOf("zzz", FixmeLabels.CHECK_TAGS, FixmeLabels.CONTINUE))
        )
        assertEquals("check name", FixmeLabels.displayName(FixmeLabels.CHECK_NAME))
        assertEquals(null, FixmeLabels.displayName(NoteLabels.SURVEYOR))
    }

    @Test
    fun `summary lines take the fixme text, the name, one main tag and the element type`() {
        val tags = mapOf("fixme" to " check name ", "name" to "Joe's", "amenity" to "cafe", "building" to "yes")
        assertEquals("check name", FixmeSummary.primaryLine(tags))
        assertEquals("Joe's · amenity=cafe · node", FixmeSummary.secondaryLine(tags, "node"))
        assertEquals("building · way", FixmeSummary.secondaryLine(mapOf("building" to "yes"), "way"))
        assertEquals("highway=footway", FixmeSummary.secondaryLine(mapOf("highway" to "footway"), null))
        assertEquals("", FixmeSummary.secondaryLine(mapOf("fixme" to "x"), "area"))
        assertEquals("Fixme with no text", FixmeSummary.primaryLine(mapOf("fixme" to " ")))
    }

    @Test
    fun `max age allows by last edit and lets unknown dates through`() {
        val now = Date(1_800_000_000_000L)
        val day = 24 * 3600 * 1000L
        val eighteenMonthsAgo = Date(now.time - 548 * day)
        assertTrue(FixmeMaxAge.ANY.allows(eighteenMonthsAgo, now))
        assertFalse(FixmeMaxAge.ONE_YEAR.allows(eighteenMonthsAgo, now))
        assertTrue(FixmeMaxAge.TWO_YEARS.allows(eighteenMonthsAgo, now))
        assertTrue(FixmeMaxAge.FIVE_YEARS.allows(eighteenMonthsAgo, now))
        assertFalse(FixmeMaxAge.FIVE_YEARS.allows(Date(now.time - 6 * 365 * day), now))
        assertTrue(FixmeMaxAge.ONE_YEAR.allows(null, now))
        assertEquals(FixmeMaxAge.TWO_YEARS, FixmeMaxAge.fromPrefValue("2y"))
        assertEquals(FixmeMaxAge.ANY, FixmeMaxAge.fromPrefValue("bogus"))
        assertEquals(FixmeMaxAge.ANY, FixmeMaxAge.fromPrefValue(null))
    }
}
