package com.surveyme.core

import java.util.Date

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

/**
 * The label vocabulary for fixme text (roadmap item 12), stored in `pois.labels` the same way
 * [NoteLabels] are stored on notes. Small on purpose: fixme text is one line, and the point of
 * a label is to tell the walker what the visit is for before they read it.
 */
object FixmeLabels {
    /** Mapping was left half done ("continue", "incomplete", "unfinished", "more to map"). */
    const val CONTINUE = "continue"
    /** A ground check is asked for ("resurvey", "survey", "check", "verify", "confirm"). */
    const val RESURVEY = "resurvey"
    /** The name is in doubt ("name", "spelling", "what is this called"). */
    const val CHECK_NAME = "check_name"
    /** The geometry is in doubt ("position", "location", "align", "move"). */
    const val CHECK_POSITION = "check_position"
    /** The tagging is in doubt ("tag", "tagging", "which", "type"). */
    const val CHECK_TAGS = "check_tags"

    /** Chip order: what to do first, then what to check. */
    val DISPLAY_ORDER = listOf(CONTINUE, RESURVEY, CHECK_NAME, CHECK_POSITION, CHECK_TAGS)

    private val DISPLAY_NAMES = mapOf(
        CONTINUE to "continue",
        RESURVEY to "resurvey",
        CHECK_NAME to "check name",
        CHECK_POSITION to "check position",
        CHECK_TAGS to "check tags"
    )

    /** The chip text for a fixme label, or null when [label] is not one (a note label, say). */
    fun displayName(label: String): String? = DISPLAY_NAMES[label]

    /** [labels] in [DISPLAY_ORDER]; anything unknown last, sorted. */
    fun ordered(labels: Set<String>): List<String> {
        val known = DISPLAY_ORDER.filter { it in labels }
        return known + (labels - known.toSet()).sorted()
    }
}

/**
 * Labels the text of a `fixme` tag. Pure Kotlin, one regex per label, whole words only and
 * case-insensitive, so "checked" does not become a resurvey and "moved" does not ask for a
 * position check. Bump [NoteClassifier.VERSION] when these rules change: the same first-launch
 * pass that relabels notes relabels stored fixmes.
 */
object FixmeClassifier {
    private val RULES: List<Pair<String, Regex>> = listOf(
        FixmeLabels.CONTINUE to words("continue", "incomplete", "unfinished", "more to map"),
        FixmeLabels.RESURVEY to words("resurvey", "re-survey", "survey", "check", "verify", "confirm"),
        FixmeLabels.CHECK_NAME to words("name", "spelling", "what is this called"),
        FixmeLabels.CHECK_POSITION to words("position", "location", "align", "move"),
        FixmeLabels.CHECK_TAGS to words("tag", "tagging", "which", "type")
    )

    private fun words(vararg phrases: String): Regex =
        Regex("\\b(?:" + phrases.joinToString("|") { Regex.escape(it) } + ")\\b", RegexOption.IGNORE_CASE)

    /** The labels for one fixme text; empty for null, blank or unrecognised text. */
    fun classify(fixme: String?): Set<String> {
        val text = fixme?.trim().orEmpty()
        if (text.isEmpty()) return emptySet()
        return RULES.filter { (_, regex) -> regex.containsMatchIn(text) }.map { it.first }.toSet()
    }
}

/**
 * The two lines a fixme takes in the list: the fixme text, and the element's main tags with a
 * compact node/way marker so the walker knows what they are looking for.
 */
object FixmeSummary {
    /**
     * Keys worth a line, most telling first. `name` always leads when present; of the rest only
     * the first key present is shown, since a cafe that is also a building is a cafe.
     */
    val MAIN_KEYS = listOf(
        "amenity", "shop", "highway", "building", "leisure", "natural", "tourism",
        "office", "craft", "man_made", "railway", "public_transport"
    )

    /** The fixme text, or a placeholder for the rare element whose `fixme=yes`-style tag says nothing. */
    fun primaryLine(tags: Map<String, String>): String =
        tags["fixme"]?.trim()?.takeIf { it.isNotEmpty() } ?: "Fixme with no text"

    /**
     * "Name · key=value · node", each part only when known. [elementType] is "node" or "way";
     * an empty result means the element has nothing telling to say about itself.
     */
    fun secondaryLine(tags: Map<String, String>, elementType: String?): String {
        val parts = mutableListOf<String>()
        tags["name"]?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        MAIN_KEYS.firstOrNull { !tags[it].isNullOrBlank() }?.let { key ->
            // building=yes says "a building", not much more; any other value is worth showing.
            val value = tags.getValue(key).trim()
            parts += if (key == "building" && value == "yes") "building" else "$key=$value"
        }
        elementType?.lowercase()?.takeIf { it == "node" || it == "way" || it == "relation" }?.let { parts += it }
        return parts.joinToString(" · ")
    }
}

/**
 * How old a fixme may be and still show or ping, by the element's last edit (Overpass
 * `timestamp`). A setting read by the Notes tab and the ping gate; item 13 moves it to Settings.
 */
enum class FixmeMaxAge(val prefValue: String, val label: String, val millis: Long?) {
    ANY("any", "Any age", null),
    ONE_YEAR("1y", "Up to 1 year", 365L * DAY_MILLIS),
    TWO_YEARS("2y", "Up to 2 years", 2 * 365L * DAY_MILLIS),
    FIVE_YEARS("5y", "Up to 5 years", 5 * 365L * DAY_MILLIS);

    /**
     * True when a fixme last edited at [lastEditedAt] is young enough. Null is unknown (a row
     * stored before v9, or an element Overpass answered without meta) and is allowed through
     * until the next fetch fills the date in.
     */
    fun allows(lastEditedAt: Date?, now: Date = Date()): Boolean {
        val limit = millis ?: return true
        val edited = lastEditedAt ?: return true
        return now.time - edited.time <= limit
    }

    companion object {
        fun fromPrefValue(value: String?): FixmeMaxAge = values().firstOrNull { it.prefValue == value } ?: ANY
    }
}
