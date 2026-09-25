package com.surveyme.core

import com.surveyme.data.model.OsmNote
import com.surveyme.data.model.Poi
import java.util.Date

/**
 * Which notes may ping (roadmap item 8), under the settings of item 13. Pure so the rule is
 * unit-testable. The `surveyAfter` date is compared against the wall clock here, not through
 * the stored [NoteLabels.SCHEDULED] label, because labels are computed when the note is stored
 * and the date passes on its own. Two rules are not settings: a stale note never pings, and a
 * scheduled note never pings before its date.
 */
object NotePingPolicy {
    /**
     * True when a POI with this note row may ping. A null [note] is a fixme or an imported
     * waypoint: those keep pinging as before. A note pings only when it is open, on osm.org
     * (not a queued placeholder), of a class in [classes] (surveyor by default; see
     * [PingSettings.classAllows]), not stale, and past its `#surveyafter` date.
     */
    fun mayPing(note: OsmNote?, now: Date = Date(), classes: Set<String> = PingSettings.DEFAULT_CLASSES): Boolean {
        if (note == null) return true
        if (!note.isOpen || note.isPending) return false
        if (!PingSettings.classAllows(classes, note.labels)) return false
        if (NoteLabels.STALE in note.labels) return false
        val after = note.surveyAfter
        if (after != null && after.after(now)) return false
        return true
    }

    /**
     * The full gate for one stored row: a note follows [mayPing] on its note row unless
     * [PingSettings.muteNotes]; a fixme pings unless [PingSettings.muteFixmes], it was
     * dismissed ([Poi.isMuted]), sits on a relation, or was last edited longer ago than
     * [PingSettings.fixmeMaxAge] allows (an unknown date passes); anything else (an imported
     * waypoint) pings as before. A note's own [Poi.isMuted] (the notification's "Mute this
     * one") is applied by the service, which drops muted rows from its cache.
     */
    fun mayPing(poi: Poi, note: OsmNote?, now: Date = Date(), settings: PingSettings = PingSettings.DEFAULT): Boolean {
        if (note != null) return !settings.muteNotes && mayPing(note, now, settings.classes)
        if (poi.isNote) return !settings.muteNotes
        if (!poi.isFixme) return true
        if (settings.muteFixmes || poi.isMuted || poi.isOnRelation) return false
        return settings.fixmeMaxAge.allows(poi.lastEditedAt, now)
    }

    /** Item 12's shape of the gate: everything at its default but the fixme age. */
    fun mayPing(poi: Poi, note: OsmNote?, now: Date, fixmeMaxAge: FixmeMaxAge): Boolean =
        mayPing(poi, note, now, PingSettings(fixmeMaxAge = fixmeMaxAge))
}
