package com.surveyme.core

/**
 * The ping gate's settings (roadmap item 13), read from [PreferencesManager.pingSettings] and
 * handed to [NotePingPolicy] as one value so the policy stays pure and testable.
 *
 * [classes] is the set of note classes allowed to ping: any of [NoteLabels.AUDIENCES] plus
 * [UNCLASSIFIED] for notes that carry no audience label at all. [muteNotes] and [muteFixmes]
 * are the master switches; they gate pings only, never the lists or the map.
 */
data class PingSettings(
    val classes: Set<String> = DEFAULT_CLASSES,
    val muteNotes: Boolean = false,
    val muteFixmes: Boolean = false,
    val fixmeMaxAge: FixmeMaxAge = FixmeMaxAge.ANY
) {
    companion object {
        /** A note with none of [NoteLabels.AUDIENCES] on it. Off by default: nobody asked for a visit. */
        const val UNCLASSIFIED = "unclassified"

        /** Surveyor only, as item 8 decided. */
        val DEFAULT_CLASSES: Set<String> = setOf(NoteLabels.SURVEYOR)

        /** Every class a user can tick, in the order the Settings screen lists them. */
        val ALL_CLASSES: List<String> = NoteLabels.AUDIENCES + UNCLASSIFIED

        val DEFAULT = PingSettings()

        /** True when a note with these labels belongs to one of [classes]. */
        fun classAllows(classes: Set<String>, labels: Set<String>): Boolean {
            val audiences = NoteLabels.AUDIENCES.filter { it in labels }
            if (audiences.isEmpty()) return UNCLASSIFIED in classes
            return audiences.any { it in classes }
        }
    }
}
