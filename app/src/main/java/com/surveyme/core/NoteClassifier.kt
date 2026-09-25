package com.surveyme.core

import com.surveyme.data.model.OsmNote
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * The label vocabulary of roadmap item 8, one string per label as stored in [OsmNote.labels].
 * Three axes (audience, topic, state) plus the strict tags mappers write and the source app.
 */
object NoteLabels {
    // Audience: who should pick the note up.
    const val SURVEYOR = "surveyor"
    const val TELEMAPPER = "telemapper"
    const val JOSM = "josm"
    val AUDIENCES = listOf(SURVEYOR, TELEMAPPER, JOSM)

    // Strict tags found in the thread (`#surveyme`, `#surveyafter DATE`, `#armchair`, `#josm`).
    const val TAG_SURVEYME = "tag_surveyme"
    const val TAG_SURVEYAFTER = "tag_surveyafter"
    const val TAG_ARMCHAIR = "tag_armchair"
    const val TAG_JOSM = "tag_josm"

    // Topic.
    const val ADDRESS = "address"
    const val BUSINESS = "business"
    const val CONSTRUCTION = "construction"
    const val TRANSIT = "transit"
    const val ROADS = "roads"
    const val CYCLING_WALKING = "cycling_walking"
    const val PARKS = "parks"
    const val ACCESSIBILITY = "accessibility"
    const val AMENITY_DETAIL = "amenity_detail"
    const val GEOMETRY = "geometry"
    const val DATA_QUALITY = "data_quality"
    const val IMPORT_FOLLOWUP = "import_followup"
    val TOPICS = listOf(
        ADDRESS, BUSINESS, CONSTRUCTION, TRANSIT, ROADS, CYCLING_WALKING, PARKS, ACCESSIBILITY,
        AMENITY_DETAIL, GEOMETRY, DATA_QUALITY, IMPORT_FOLLOWUP
    )

    // State.
    const val SURVEY_REQUESTED = "survey_requested"
    const val SCHEDULED = "scheduled"
    const val FUTURE_DATED = "future_dated"
    const val APP_OBSERVED_NEEDS_EDIT = "app_observed_needs_edit"
    const val APP_NEEDS_GROUND_CHECK = "app_needs_ground_check"
    const val HAS_ELEMENT_LINK = "has_element_link"
    const val HAS_PHOTO = "has_photo"
    const val HAS_SOURCE_LINK = "has_source_link"
    const val DUPLICATE_CLUSTER = "duplicate_cluster"
    const val DISCUSSED = "discussed"
    const val ANONYMOUS = "anonymous"
    const val STALE = "stale"
    const val LOW_SIGNAL = "low_signal"
    const val MINE = "mine"
    const val NOTE_TO_SELF = "note_to_self"

    // Source app. Shown, never a filter.
    const val APP_STREETCOMPLETE = "app_streetcomplete"
    const val APP_ORGANICMAPS = "app_organicmaps"
    const val APP_COMAPS = "app_comaps"
    const val APP_EVERYDOOR = "app_everydoor"
    val APPS = listOf(APP_STREETCOMPLETE, APP_ORGANICMAPS, APP_COMAPS, APP_EVERYDOOR)

    /** Chip order: audience, strict tags, the states that change what to do, topics, the rest. */
    val DISPLAY_ORDER: List<String> = AUDIENCES +
        listOf(TAG_SURVEYME, TAG_SURVEYAFTER, TAG_ARMCHAIR, TAG_JOSM) +
        listOf(SCHEDULED, SURVEY_REQUESTED, MINE, NOTE_TO_SELF, STALE, DUPLICATE_CLUSTER) +
        TOPICS +
        listOf(APP_OBSERVED_NEEDS_EDIT, APP_NEEDS_GROUND_CHECK, FUTURE_DATED) +
        APPS +
        listOf(HAS_ELEMENT_LINK, HAS_PHOTO, HAS_SOURCE_LINK, DISCUSSED, ANONYMOUS, LOW_SIGNAL)

    private val DISPLAY_NAMES = mapOf(
        SURVEYOR to "Surveyor", TELEMAPPER to "Telemapper", JOSM to "JOSM",
        TAG_SURVEYME to "#surveyme", TAG_SURVEYAFTER to "#surveyafter", TAG_ARMCHAIR to "#armchair", TAG_JOSM to "#josm",
        ADDRESS to "address", BUSINESS to "business", CONSTRUCTION to "construction", TRANSIT to "transit",
        ROADS to "roads", CYCLING_WALKING to "cycling & walking", PARKS to "parks", ACCESSIBILITY to "accessibility",
        AMENITY_DETAIL to "amenity detail", GEOMETRY to "geometry", DATA_QUALITY to "data quality",
        IMPORT_FOLLOWUP to "import follow-up",
        SURVEY_REQUESTED to "survey requested", SCHEDULED to "scheduled", FUTURE_DATED to "future date?",
        APP_OBSERVED_NEEDS_EDIT to "observed, needs edit", APP_NEEDS_GROUND_CHECK to "needs ground check",
        HAS_ELEMENT_LINK to "element link", HAS_PHOTO to "photo", HAS_SOURCE_LINK to "source link",
        DUPLICATE_CLUSTER to "cluster", DISCUSSED to "discussed", ANONYMOUS to "anonymous", STALE to "stale",
        LOW_SIGNAL to "low signal", MINE to "mine", NOTE_TO_SELF to "note to self",
        APP_STREETCOMPLETE to "StreetComplete", APP_ORGANICMAPS to "Organic Maps", APP_COMAPS to "CoMaps",
        APP_EVERYDOOR to "EveryDoor"
    )

    fun displayName(label: String): String = DISPLAY_NAMES[label] ?: label.replace('_', ' ')

    /** [labels] in [DISPLAY_ORDER], unknown ones last. */
    fun ordered(labels: Set<String>): List<String> {
        val known = DISPLAY_ORDER.filter { it in labels }
        return known + (labels - known.toSet()).sorted()
    }

    /** The audience a note is coloured by when it carries several: surveyor, then telemapper, then JOSM. */
    fun audienceOf(labels: Set<String>): String? = AUDIENCES.firstOrNull { it in labels }
}

/** What [NoteClassifier.classify] answers: the labels and the strict `#surveyafter` date, if any. */
data class NoteClassification(val labels: Set<String>, val surveyAfter: Date?)

/**
 * Built-in note classifier: a faithful port of `analysis/classify_notes.py` (decided 2026-09-23
 * from 305 open Toronto notes), plus the strict tag vocabulary of the roadmap. Pure Kotlin, no
 * Android; it takes an [OsmNote] and answers labels from [NoteLabels].
 *
 * Rules of thumb kept from the prototype:
 * - topics are matched on the opening comment with app boilerplate stripped; the `app_*`,
 *   `has_*` and strict-tag patterns on the raw thread; everything else on the stripped thread;
 * - loose date parsing only produces the [NoteLabels.FUTURE_DATED] hint; only a strict
 *   `#surveyafter` date hides a note ([NoteLabels.SCHEDULED]);
 * - strict tags written by mappers replace the heuristic audience; topics and states stay.
 *
 * Bump [VERSION] when the rules change so stored labels get recomputed once on next launch.
 */
class NoteClassifier {

    companion object {
        /** Stored in prefs; a higher value here triggers one reclassification of all notes. */
        const val VERSION = 1

        /**
         * Two notes of one author, one day and near-identical text closer than this are a
         * cluster. The prototype's `radius_m * 4` with radius 150 m; the roadmap says "about
         * 150 m", the count it reports (21 notes) comes from 600.
         */
        const val CLUSTER_RADIUS_METERS = 600.0

        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

        /** Boilerplate emitted by apps; stripped before keyword and date matching. */
        private val BOILERPLATE = listOf(
            """osm snapshot date: \S+""",
            """via streetcomplete(_ee)? [\d.]+""",
            """#organicmaps \S+ \S+""",
            """#comaps \S+ \S+""",
            """a (comaps|organic maps)? ?user (of organic maps application )?(has )?reported that the poi was visible on the map \(see snapshot date below\), but was not found on the ground\.?""",
            """the place has gone or never existed\.?""",
            """attached photo\(s\):( \S+)*"""
        ).map { Regex(it) }

        private val TOPIC_PATTERNS: Map<String, Regex> = mapOf(
            NoteLabels.ADDRESS to """\baddress|house ?number|addressing|interpolation|\bunits?\b|\bentrances?\b|building number|laneway|lanehouse|lane housing""",
            NoteLabels.BUSINESS to """coming soon|closing|to become|now a |is it closed|still (a |open|here|in business)|what has .* become|changed name|renamed|no longer|self storage|opening 20\d\d|is this still|not in business|does not exist|has gone|torn down|check if( this is)? still|closed (over|several)""",
            NoteLabels.CONSTRUCTION to """construction|demolish|demolition|redevelop|new (building|townhouse|condo|highrise|semi)|being built|development|coming here|tall building|highrises|towers? built""",
            NoteLabels.TRANSIT to """\bttc\b|\bgo (station|bus|transit|\d)|bus (stop|loop|terminal|route)|subway|station|platform|metrolinx|ontario line|up express|presto|busway|streetcar|tram""",
            NoteLabels.ROADS to """turn restriction|\blanes?\b|\bhov\b|maxspeed|km ?per ?hour|km/h|traffic (signal|light|control)|red light camera|one[- ]way|no left|routing|motorway|\blinks?\b should|median|road (layout|is)|widening|highway exit|intersection|area signs""",
            NoteLabels.CYCLING_WALKING to """bike|bicycle|cycle|cycleway|sharrow|contraflow|footpath|footway|\btrail\b|\bpath\b|crossing|crosswalk|curb|tactile|walkway|pedestrian|stairs|ramp|\bford\b""",
            NoteLabels.PARKS to """\bparks?\b|playground|pitch|tennis|golf|cemetery|greenspace|trail system|waterfront|creek|putting green|tee box""",
            NoteLabels.ACCESSIBILITY to """wheelchair|elevator|\blift\b|tactile|accessib|stairs inside|ramp""",
            NoteLabels.AMENITY_DETAIL to """opening hours|washroom|toilet|bench|shelter|recycling|collection box|pickup times|bottle refill|drinking water|little free library|parking lot|who is allowed to park|food ?court|plaque|artwork|billboard|memorial""",
            NoteLabels.GEOMETRY to """misalign|re-?align|alignment|building shapes|offset|orthoimagery|aerial|imagery|satellite|polygon|split|merge|glued|terraced|perimeter|boundary|position of|exact (location|position)""",
            NoteLabels.DATA_QUALITY to """category|poi types|\btagged\b|tagging|tag this|should be tagged|relation|duplicate|same id|incorrectly labeled|should not have names""",
            NoteLabels.IMPORT_FOLLOWUP to """import|canvec|geobase|city data|open data|interpolation|osb id"""
        ).mapValues { Regex(it.value) }

        // Matched on the stripped thread.
        private val SURVEY_REQUESTED = Regex("""\bsurvey\b|re-?survey|surveying|#surveyme|needs? a look|would benefit from a look|please (confirm|check|verify|review|double-check)|double-?check|check (if|where|whether)|is (this|it|there)( still|.*\?)|requires survey|verification|not sure if""")
        private val PLANNED_CHANGE = Regex("""coming soon|\bfuture\b|\bplanned\b|\bproposed\b|to be (demolished|installed|upgraded|built)|estimated completion|will (be|need|have)|slated|planned for|coming 20\d\d""")
        private val NOTE_TO_SELF = Regex("""note to self""")

        // Matched on the raw (lowercased) thread.
        private val APP_STREETCOMPLETE = Regex("""via streetcomplete""")
        private val APP_ORGANICMAPS = Regex("""#organicmaps|organic maps""")
        private val APP_COMAPS = Regex("""#comaps|comaps user""")
        private val APP_EVERYDOOR = Regex("""#everydoor""")
        private val HAS_ELEMENT_LINK = Regex("""osm\.org/(node|way|relation)/\d+|openstreetmap\.org/(node|way|relation)/\d+""")
        private val HAS_PHOTO = Regex("""attached photo|\.jpe?g\b|\.png\b""")
        private val HAS_SOURCE_LINK = Regex("""https?://(?!osm\.org|www\.openstreetmap\.org|streetcomplete\.app|westnordost\.de)""")
        private val TAG_SURVEYME = Regex("""#surveyme\b|#resurvey\b""")
        private val TAG_SURVEYAFTER = Regex("""#surveyafter\s+(\d{4})-(\d{2})(?:-(\d{2}))?""")
        private val TAG_ARMCHAIR = Regex("""#armchair\b""")
        private val TAG_JOSM = Regex("""#josm\b""")

        // App reports: the reporter's own words, and whether they ask rather than state.
        private val STREETCOMPLETE_FREE_TEXT = Regex("""via streetcomplete[^:]*:\s*(.*)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val QUOTED_FREE_TEXT = Regex("""^\s*"([^"]*)"""")
        private val HEDGED = Regex("""not sure|unsure|maybe|might|could be|possibly""")

        private val WORD = Regex("""\w+""")
        private val WHITESPACE = Regex("""\s+""")

        private const val MONTHS = "january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec"
        private val MONTH_ABBREVIATIONS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

        /** Loose date patterns; each answers (year, month, day) or null when it does not apply. */
        private val DATE_PATTERNS: List<Pair<Regex, (MatchResult) -> Triple<Int, Int, Int>?>> = listOf(
            Regex("""\b(20\d\d)-(\d\d)(?:-(\d\d))?\b""") to { m ->
                Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull() ?: 1)
            },
            Regex("""\b($MONTHS)\.? (\d{1,2}),? (20\d\d)\b""") to { m ->
                Triple(m.groupValues[3].toInt(), monthOf(m.groupValues[1]), m.groupValues[2].toInt())
            },
            Regex("""\b($MONTHS)\.? (20\d\d)\b""") to { m ->
                Triple(m.groupValues[2].toInt(), monthOf(m.groupValues[1]), 1)
            },
            Regex("""\b(spring|summer|fall|autumn|winter) (20\d\d)\b""") to { m ->
                val month = when (m.groupValues[1]) {
                    "spring" -> 4
                    "summer" -> 7
                    "fall", "autumn" -> 10
                    else -> 1
                }
                Triple(m.groupValues[2].toInt(), month, 1)
            },
            Regex("""\b(?:by|in|until|for) (20\d\d)\b""") to { m ->
                Triple(m.groupValues[1].toInt(), 12, 31)
            }
        )

        private fun monthOf(name: String): Int = MONTH_ABBREVIATIONS.indexOf(name.take(3).lowercase(Locale.ROOT)) + 1

        /** Whole days since the epoch, in UTC: the prototype's `date[:10]` bucketing. */
        private fun dayIndex(date: Date): Long = Math.floorDiv(date.time, DAY_MILLIS)

        /**
         * Midnight UTC of a calendar date, or null when the month is out of range (the
         * prototype's `datetime.date` raises and the match is skipped). Day is capped at 28
         * like the prototype so "February 30" still counts.
         */
        private fun utcDate(year: Int, month: Int, day: Int): Date? {
            if (month !in 1..12 || day < 1) return null
            val calendar = Calendar.getInstance(UTC, Locale.US)
            calendar.isLenient = false
            calendar.clear()
            calendar.set(year, month - 1, min(day, 28), 0, 0, 0)
            return try {
                calendar.time
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun stripBoilerplate(text: String): String {
            var out = text.lowercase(Locale.ROOT)
            for (pattern in BOILERPLATE) out = out.replace(pattern, " ")
            return out
        }

        /** The prototype's [_dist]: equirectangular metres between two points. */
        private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = (lat1 - lat2) * 111320.0
            val dLon = (lon1 - lon2) * 111320.0 * cos(Math.toRadians(lat1))
            return hypot(dLat, dLon)
        }
    }

    /**
     * Labels and strict survey-after date for [note]. [ownDisplayName] is the logged-in user's
     * name for [NoteLabels.MINE], null when logged out; [today] fixes "now" for age and date
     * parsing. [NoteLabels.DUPLICATE_CLUSTER] is not decided here, see [markClusters].
     */
    fun classify(note: OsmNote, ownDisplayName: String? = null, today: Date = Date()): NoteClassification {
        val comments = note.comments
        val opening = comments.firstOrNull()
        val firstRaw = opening?.text ?: ""
        val fullRaw = comments.joinToString(" \n ") { it.text }
        val fullRawLower = fullRaw.lowercase(Locale.ROOT)
        val first = stripBoilerplate(firstRaw)
        val full = stripBoilerplate(fullRaw)

        val todayIndex = dayIndex(today)
        val ageDays = todayIndex - dayIndex(note.createdAt)
        val lastActivity = comments.lastOrNull()?.date ?: note.createdAt
        val sinceLastDays = todayIndex - dayIndex(lastActivity)

        val topics = TOPIC_PATTERNS.filterValues { it.containsMatchIn(first) }.keys
        val labels = mutableSetOf<String>()
        labels += topics

        // ---- state from text ----------------------------------------------------------------
        if (SURVEY_REQUESTED.containsMatchIn(full)) labels += NoteLabels.SURVEY_REQUESTED
        if (NOTE_TO_SELF.containsMatchIn(full)) labels += NoteLabels.NOTE_TO_SELF
        if (APP_STREETCOMPLETE.containsMatchIn(fullRawLower)) labels += NoteLabels.APP_STREETCOMPLETE
        if (APP_ORGANICMAPS.containsMatchIn(fullRawLower)) labels += NoteLabels.APP_ORGANICMAPS
        if (APP_COMAPS.containsMatchIn(fullRawLower)) labels += NoteLabels.APP_COMAPS
        if (APP_EVERYDOOR.containsMatchIn(fullRawLower)) labels += NoteLabels.APP_EVERYDOOR
        if (HAS_ELEMENT_LINK.containsMatchIn(fullRawLower)) labels += NoteLabels.HAS_ELEMENT_LINK
        if (HAS_PHOTO.containsMatchIn(fullRawLower)) labels += NoteLabels.HAS_PHOTO
        if (HAS_SOURCE_LINK.containsMatchIn(fullRawLower)) labels += NoteLabels.HAS_SOURCE_LINK

        // ---- state from metadata --------------------------------------------------------------
        if (comments.size > 1) labels += NoteLabels.DISCUSSED
        if (opening?.author == null) labels += NoteLabels.ANONYMOUS
        if (ageDays > 730 && sinceLastDays > 365) labels += NoteLabels.STALE
        if (futureDates(full, todayIndex).isNotEmpty() || PLANNED_CHANGE.containsMatchIn(full)) labels += NoteLabels.FUTURE_DATED
        if (ownDisplayName != null && opening?.author == ownDisplayName) labels += NoteLabels.MINE

        val app = NoteLabels.APPS.any { it in labels }
        val words = WORD.findAll(first).count()
        if (words <= 3 && topics.isEmpty() && !app) labels += NoteLabels.LOW_SIGNAL

        // App reports: did the reporter already observe the answer, or are they asking?
        if (app) {
            // StreetComplete free text follows the last ": " after the quest; OM text is the quoted part.
            val free = STREETCOMPLETE_FREE_TEXT.find(firstRaw)?.groupValues?.get(1)
                ?: QUOTED_FREE_TEXT.find(firstRaw)?.groupValues?.get(1)
                ?: firstRaw
            val freeLower = free.trim().lowercase(Locale.ROOT)
            if (freeLower.isEmpty() || '?' in freeLower || HEDGED.containsMatchIn(freeLower)) {
                labels += NoteLabels.APP_NEEDS_GROUND_CHECK
            } else {
                labels += NoteLabels.APP_OBSERVED_NEEDS_EDIT
            }
        }

        // ---- heuristic audience ---------------------------------------------------------------
        val audience = mutableSetOf<String>()
        if (NoteLabels.APP_OBSERVED_NEEDS_EDIT in labels || NoteLabels.GEOMETRY in topics) audience += NoteLabels.TELEMAPPER
        if (NoteLabels.HAS_SOURCE_LINK in labels && NoteLabels.SURVEY_REQUESTED !in labels) audience += NoteLabels.TELEMAPPER
        if (NoteLabels.SURVEY_REQUESTED in labels || NoteLabels.APP_NEEDS_GROUND_CHECK in labels ||
            topics.any { it == NoteLabels.ADDRESS || it == NoteLabels.CYCLING_WALKING || it == NoteLabels.AMENITY_DETAIL || it == NoteLabels.ACCESSIBILITY }
        ) audience += NoteLabels.SURVEYOR
        if (NoteLabels.BUSINESS in topics && NoteLabels.APP_OBSERVED_NEEDS_EDIT !in labels) audience += NoteLabels.SURVEYOR
        if (topics.any { it == NoteLabels.TRANSIT || it == NoteLabels.ROADS || it == NoteLabels.DATA_QUALITY || it == NoteLabels.IMPORT_FOLLOWUP }) audience += NoteLabels.JOSM

        // ---- strict tags win ------------------------------------------------------------------
        val strictAudience = mutableSetOf<String>()
        var surveyAfter: Date? = null
        if (TAG_SURVEYME.containsMatchIn(fullRawLower)) {
            labels += NoteLabels.TAG_SURVEYME
            strictAudience += NoteLabels.SURVEYOR
        }
        TAG_SURVEYAFTER.find(fullRawLower)?.let { m ->
            val date = utcDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull() ?: 1)
            if (date != null) {
                labels += NoteLabels.TAG_SURVEYAFTER
                strictAudience += NoteLabels.SURVEYOR
                surveyAfter = date
            }
        }
        if (TAG_ARMCHAIR.containsMatchIn(fullRawLower)) {
            labels += NoteLabels.TAG_ARMCHAIR
            strictAudience += NoteLabels.TELEMAPPER
        }
        if (TAG_JOSM.containsMatchIn(fullRawLower)) {
            labels += NoteLabels.TAG_JOSM
            strictAudience += NoteLabels.JOSM
        }
        labels += if (strictAudience.isNotEmpty()) strictAudience else audience

        surveyAfter?.let { after ->
            if (dayIndex(after) > todayIndex) {
                // Not wanted yet: hidden from pings until the date, then it is a survey request.
                labels += NoteLabels.SCHEDULED
                labels -= NoteLabels.SURVEY_REQUESTED
            } else {
                labels += NoteLabels.SURVEY_REQUESTED
            }
        }
        if (NoteLabels.TAG_SURVEYME in labels && NoteLabels.SCHEDULED !in labels) labels += NoteLabels.SURVEY_REQUESTED

        return NoteClassification(labels, surveyAfter)
    }

    /** Dates in [text] (stripped, lowercased) that fall after today, by the prototype's loose patterns. */
    private fun futureDates(text: String, todayIndex: Long): List<Date> {
        val out = mutableListOf<Date>()
        for ((pattern, convert) in DATE_PATTERNS) {
            for (match in pattern.findAll(text)) {
                val (year, month, day) = convert(match) ?: continue
                val date = utcDate(year, month, day) ?: continue
                if (dayIndex(date) > todayIndex) out += date
            }
        }
        return out
    }

    /** A note with the position of its POI row, for [markClusters]. */
    data class ClusterCandidate(val note: OsmNote, val latitude: Double, val longitude: Double)

    /**
     * Note ids that sit in a duplicate cluster: same author, same (UTC) day, same first four
     * words, and another member within [CLUSTER_RADIUS_METERS]. Computed over a neighbourhood,
     * not one note, so the repository calls it with the fetched batch plus what is stored.
     */
    fun markClusters(candidates: List<ClusterCandidate>): Set<Long> {
        val groups = candidates.groupBy { c ->
            val firstText = WHITESPACE.replace(c.note.openingText, " ").take(120).lowercase(Locale.ROOT)
            val head = WORD.findAll(firstText).take(4).joinToString(" ") { it.value }
            Triple(c.note.comments.firstOrNull()?.author ?: "", dayIndex(c.note.createdAt), head)
        }
        val out = mutableSetOf<Long>()
        for (group in groups.values) {
            if (group.size < 2) continue
            for (member in group) {
                val near = group.any { other ->
                    other !== member &&
                        distanceMeters(member.latitude, member.longitude, other.latitude, other.longitude) <= CLUSTER_RADIUS_METERS
                }
                if (near) out += member.note.noteId
            }
        }
        return out
    }
}
