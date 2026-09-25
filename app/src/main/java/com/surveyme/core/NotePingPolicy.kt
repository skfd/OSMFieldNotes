package com.surveyme.core

import com.surveyme.data.model.OsmNote
import java.util.Date

/**
 * Which notes may ping (roadmap item 8). Pure so the rule is unit-testable and item 13 can
 * turn it into a setting. The `surveyAfter` date is compared against the wall clock here,
 * not through the stored [NoteLabels.SCHEDULED] label, because labels are computed when the
 * note is stored and the date passes on its own.
 */
object NotePingPolicy {
    /**
     * True when a POI with this note row may ping. A null [note] is a fixme or an imported
     * waypoint: those keep pinging as before. A note pings only when it is open, on osm.org
     * (not a queued placeholder), surveyor-class, not stale, and past its `#surveyafter` date.
     */
    fun mayPing(note: OsmNote?, now: Date = Date()): Boolean {
        if (note == null) return true
        if (!note.isOpen || note.isPending) return false
        if (NoteLabels.SURVEYOR !in note.labels) return false
        if (NoteLabels.STALE in note.labels) return false
        val after = note.surveyAfter
        if (after != null && after.after(now)) return false
        return true
    }
}
