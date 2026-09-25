package com.surveyme.core

import com.surveyme.data.model.OsmNote
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Morning triage (roadmap item 9): which stored notes are worth a look since the last triage,
 * what the heuristic guess for each is, and the strict-tag comment a tap posts. Pure Kotlin so
 * the filter is unit-testable and the Home card and the triage list agree by construction.
 *
 * "New" means the note was created after the last triage stamp; a stamp of [NEVER] means every
 * open note is new. The stamp is taken when the triage screen is opened, so a note fetched
 * during the session with a later creation date shows up next time.
 */
object NoteTriage {
    /** [lastTriageAt] value before the first Done: everything open is new. */
    const val NEVER = 0L

    /** The four strict tags a tap posts, as comment text. */
    const val TAG_SURVEYME = "#surveyme"
    const val TAG_SURVEYAFTER = "#surveyafter"
    const val TAG_ARMCHAIR = "#armchair"
    const val TAG_JOSM = "#josm"

    private val ISO_DAY: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Open notes that osm.org knows about; closed ones and queued placeholders are not triaged. */
    fun isTriageable(note: OsmNote): Boolean = note.isOpen && !note.isPending

    /** Created after the last stamp, or never triaged. */
    fun isNew(note: OsmNote, lastTriageAt: Long): Boolean =
        lastTriageAt == NEVER || note.createdAt.time > lastTriageAt

    /**
     * The triage list: triageable notes, only the new ones unless [showAll], newest first.
     * [skipped] are note ids hidden for this session.
     */
    fun select(
        notes: List<OsmNote>,
        lastTriageAt: Long,
        showAll: Boolean = false,
        skipped: Set<Long> = emptySet()
    ): List<OsmNote> = notes
        .filter { isTriageable(it) && it.noteId !in skipped && (showAll || isNew(it, lastTriageAt)) }
        .sortedByDescending { it.createdAt }

    /** The strict tag a note already carries (first by priority), or null. */
    fun strictTagOf(labels: Set<String>): String? = when {
        NoteLabels.TAG_SURVEYAFTER in labels -> TAG_SURVEYAFTER
        NoteLabels.TAG_SURVEYME in labels -> TAG_SURVEYME
        NoteLabels.TAG_ARMCHAIR in labels -> TAG_ARMCHAIR
        NoteLabels.TAG_JOSM in labels -> TAG_JOSM
        else -> null
    }

    /**
     * The tag the heuristics suggest, to pre-highlight: surveyor notes get `#surveyme`, or
     * `#surveyafter` when the text hints at a future date; telemapper `#armchair`; JOSM `#josm`.
     * Null when no audience rule matched.
     */
    fun guessFor(labels: Set<String>): String? = when (NoteLabels.audienceOf(labels)) {
        NoteLabels.SURVEYOR -> if (NoteLabels.FUTURE_DATED in labels) TAG_SURVEYAFTER else TAG_SURVEYME
        NoteLabels.TELEMAPPER -> TAG_ARMCHAIR
        NoteLabels.JOSM -> TAG_JOSM
        else -> null
    }

    /**
     * The comment text for [tag]. `#surveyafter` needs [date] (a UTC-midnight day, as the date
     * picker answers) and is written as `#surveyafter YYYY-MM-DD`, the form the classifier reads.
     */
    fun commentFor(tag: String, date: Date? = null): String = when (tag) {
        TAG_SURVEYAFTER -> "$TAG_SURVEYAFTER ${ISO_DAY.format(requireNotNull(date) { "#surveyafter needs a date" })}"
        else -> tag
    }
}
