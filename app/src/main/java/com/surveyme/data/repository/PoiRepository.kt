package com.surveyme.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.surveyme.core.Constants
import com.surveyme.core.FixmeClassifier
import com.surveyme.core.FixmeMaxAge
import com.surveyme.core.NoteClassifier
import com.surveyme.core.NoteLabels
import com.surveyme.core.NotePingPolicy
import com.surveyme.data.FetchedNote
import com.surveyme.data.OsmNotesService
import com.surveyme.data.OverpassService
import com.surveyme.data.database.NoteActionDao
import com.surveyme.data.database.PoiWithNote
import com.surveyme.data.database.SurveyMeDatabase
import com.surveyme.data.geojson.GeoJsonParser
import com.surveyme.data.gpx.GpxParser
import com.surveyme.data.model.Evidence
import com.surveyme.data.model.NoteActionRequest
import com.surveyme.data.model.NoteStatus
import com.surveyme.data.model.OsmNote
import com.surveyme.data.model.Poi
import com.surveyme.data.model.PoiCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.InputStream
import java.util.Date

/**
 * [ownDisplayName] answers the logged-in OSM user's name (null when logged out) so the
 * classifier can label the user's own notes; it is a function because login changes at run time.
 * [fixmeMaxAge] answers the fixme age setting the ping gate applies (item 12), read the same way.
 */
class PoiRepository(
    private val database: SurveyMeDatabase,
    private val context: Context,
    private val ownDisplayName: () -> String? = { null },
    private val classifier: NoteClassifier = NoteClassifier(),
    private val fixmeMaxAge: () -> FixmeMaxAge = { FixmeMaxAge.ANY }
) {
    private val poiDao = database.poiDao()
    private val noteDao = database.noteDao()
    private val noteActionDao = database.noteActionDao()
    private val evidenceDao = database.evidenceDao()
    private val gpxParser = GpxParser()
    private val geoJsonParser = GeoJsonParser()

    fun getAllActivePois(): Flow<List<Poi>> = poiDao.getAllActivePois()

    fun getPoisByCategory(category: PoiCategory): Flow<List<Poi>> =
        poiDao.getPoisByCategory(category)

    fun getNotesAndFixmes(): Flow<List<Poi>> = poiDao.getNotesAndFixmes()

    /** Notes tab rows: each POI with its structured note when it has one. */
    fun getNotesAndFixmesWithNote(): Flow<List<PoiWithNote>> = poiDao.getNotesAndFixmesWithNote()

    /** Every active POI with its note row, for the map's marker colours. */
    fun getAllActivePoisWithNote(): Flow<List<PoiWithNote>> = poiDao.getAllActivePoisWithNote()

    /**
     * What proximity may ping: waypoints as before, fixmes that are not dismissed, not on a
     * relation and within the fixme age setting (item 12), and notes that pass
     * [NotePingPolicy] (open, surveyor-class, not stale, past their `#surveyafter` date). The
     * clock and the age setting are read when the flow emits, so a note crossing its date or a
     * changed setting is picked up on the next database change (a refetch while tracking, at
     * the latest 15 min).
     */
    fun getPingablePois(): Flow<List<Poi>> = poiDao.getPingableCandidates().map { rows ->
        val now = Date()
        val maxAge = fixmeMaxAge()
        rows.filter { NotePingPolicy.mayPing(it.poi, it.note, now, maxAge) }.map { it.poi }
    }

    suspend fun getNoteByPoiId(poiId: String): OsmNote? = noteDao.getByPoiId(poiId)

    fun observeNoteByPoiId(poiId: String): Flow<OsmNote?> = noteDao.observeByPoiId(poiId)

    fun getUnvisitedPois(): Flow<List<Poi>> = poiDao.getUnvisitedPois()

    suspend fun getPoiById(id: String): Poi? = poiDao.getPoiById(id)

    suspend fun getPoisInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<Poi> = poiDao.getPoisInBounds(minLat, maxLat, minLon, maxLon)

    suspend fun insertPoi(poi: Poi) = poiDao.insertPoi(poi)

    /**
     * Insert or replace, keeping the per-user flags (muted, visited, last ping) of rows
     * that already exist. Refetching notes must not un-mute a note.
     */
    suspend fun insertPois(pois: List<Poi>) {
        if (pois.isEmpty()) return
        val existing = pois.map { it.id }
            .chunked(500)
            .flatMap { poiDao.getPoisByIds(it) }
            .associateBy { it.id }
        val merged = pois.map { poi ->
            val old = existing[poi.id] ?: return@map poi
            poi.copy(
                isMuted = old.isMuted,
                isVisited = old.isVisited,
                lastNotificationTime = old.lastNotificationTime
            )
        }
        poiDao.insertPois(merged)
    }

    /**
     * Insert or replace note rows. [OsmNote.labels] and [OsmNote.surveyAfter] are recomputed
     * by the classifier on every store: they are derived from the thread, and the thread may
     * have changed (a `#surveyafter` comment added, say). A note with a close or reopen still
     * waiting in the queue keeps that optimistic status, so a background refetch does not flip
     * it back before the post went through. Takes [FetchedNote]s because the cluster rule
     * needs positions.
     */
    suspend fun insertNotes(fetched: List<FetchedNote>) {
        if (fetched.isEmpty()) return
        val ids = fetched.map { it.note.noteId }
        val existing = ids.chunked(500)
            .flatMap { noteDao.getByNoteIds(it) }
            .associateBy { it.noteId }
        val pendingStatus = ids.chunked(500)
            .flatMap { noteActionDao.getForNotes(it) }
            .groupBy { it.noteId }
            .mapValues { (_, actions) -> actions.mapNotNull { it.kind.resultingStatus }.lastOrNull() }
        val merged = fetched.map { f ->
            var out = f.note
            pendingStatus[out.noteId]?.let { status ->
                if (out.status != status) out = out.copy(status = status, closedAt = existing[out.noteId]?.closedAt ?: out.closedAt)
            }
            out to f.poi
        }
        noteDao.insertAll(classify(merged))
    }

    /**
     * Labels and survey-after date for each (note, POI) pair, with the cluster rule run over
     * the batch plus every other stored note (their positions come from their POI rows).
     * Only the batch is returned; stored notes get their own turn on the next full fetch.
     */
    private suspend fun classify(batch: List<Pair<OsmNote, Poi>>): List<OsmNote> {
        val now = Date()
        val me = ownDisplayName()
        val labelled = batch.map { (note, poi) ->
            val result = classifier.classify(note, me, now)
            note.copy(labels = result.labels, surveyAfter = result.surveyAfter) to poi
        }
        val batchIds = labelled.map { it.first.noteId }.toSet()
        val stored = noteDao.getAll().filter { it.noteId !in batchIds }
        val storedPois = stored.map { it.poiId }
            .chunked(500)
            .flatMap { poiDao.getPoisByIds(it) }
            .associateBy { it.id }
        val candidates = labelled.map { (note, poi) -> NoteClassifier.ClusterCandidate(note, poi.latitude, poi.longitude) } +
            stored.mapNotNull { note -> storedPois[note.poiId]?.let { NoteClassifier.ClusterCandidate(note, it.latitude, it.longitude) } }
        val clustered = classifier.markClusters(candidates)
        return labelled.map { (note, _) ->
            if (note.noteId in clustered) note.copy(labels = note.labels + NoteLabels.DUPLICATE_CLUSTER) else note
        }
    }

    /**
     * Recompute labels and survey-after for every stored note, and the labels of every stored
     * fixme (item 12). Run once after an upgrade that changed the rules ([NoteClassifier.VERSION])
     * and after a login or logout, since the [NoteLabels.MINE] label depends on who is logged
     * in. Returns how many notes and fixmes were relabelled.
     */
    suspend fun reclassifyAll(): Int = database.withTransaction {
        val fixmes = reclassifyFixmes()
        val notes = noteDao.getAll()
        if (notes.isEmpty()) return@withTransaction fixmes
        val pois = notes.map { it.poiId }
            .chunked(500)
            .flatMap { poiDao.getPoisByIds(it) }
            .associateBy { it.id }
        val pairs = notes.mapNotNull { note -> pois[note.poiId]?.let { note to it } }
        noteDao.insertAll(classify(pairs))
        Timber.i("Reclassified ${pairs.size} notes")
        pairs.size + fixmes
    }

    /** Fixme labels come from the `fixme` tag alone; rewrite only the rows whose labels changed. */
    private suspend fun reclassifyFixmes(): Int {
        val changed = poiDao.getPoisBySource(OverpassService.SOURCE_FIXME).mapNotNull { poi ->
            val labels = FixmeClassifier.classify(poi.tags["fixme"])
            if (labels == poi.labels) null else poi.copy(labels = labels)
        }
        if (changed.isNotEmpty()) {
            changed.chunked(500).forEach { poiDao.insertPois(it) }
            Timber.i("Relabelled ${changed.size} fixmes")
        }
        return changed.size
    }

    /**
     * One note as osm.org just answered it (after a post, or a refresh after a refused post).
     * Same merge as a fetch: ping flags stay on the POI, labels and surveyAfter on the note.
     * [completedActionId] is the queue row this answer settles; it goes in the same transaction
     * so the merge does not re-apply its optimistic status over the server's.
     */
    suspend fun storeNoteFromServer(fetched: FetchedNote, completedActionId: Long? = null) = database.withTransaction {
        completedActionId?.let { noteActionDao.delete(it) }
        insertPois(listOf(fetched.poi))
        insertNotes(listOf(fetched))
    }

    /** The optimistic status for a queued close or reopen; [OsmNote.closedAt] follows it. */
    suspend fun setNoteStatusLocally(noteId: Long, status: NoteStatus, now: Long = System.currentTimeMillis()) {
        noteDao.setStatus(noteId, status, if (status == NoteStatus.CLOSED) Date(now) else null)
    }

    suspend fun getNoteById(noteId: Long): OsmNote? = noteDao.getByNoteIds(listOf(noteId)).firstOrNull()

    /**
     * A note the user just composed (item 7): the placeholder note and POI rows and the queued
     * `create` row go in one transaction, so a crash in between cannot leave a placeholder
     * without its request or a request whose success path finds nothing to replace.
     * Returns the queue row id.
     */
    suspend fun insertPlaceholderNote(note: OsmNote, poi: Poi, request: NoteActionRequest): Long =
        database.withTransaction {
            poiDao.insertPoi(poi)
            // Labelled like any note (topics, "mine"); no cluster rule, it is not on osm.org yet.
            val result = classifier.classify(note, ownDisplayName())
            noteDao.insertAll(listOf(note.copy(labels = result.labels, surveyAfter = result.surveyAfter)))
            noteActionDao.insert(request)
        }

    /**
     * osm.org answered a create: the placeholder rows go, the server's note comes in under its
     * real id, and the queue row is settled, all in one transaction. [fetched] must already
     * carry the flags the caller wants on the new POI (the merge in [insertPois] only keeps
     * flags of a row with the same id, and this id is new).
     */
    suspend fun replacePlaceholderWithServerNote(placeholderNoteId: Long, fetched: FetchedNote, completedActionId: Long) =
        database.withTransaction {
            // Evidence pinned to the placeholder (item 11) follows the note to its real id.
            evidenceDao.rekey(OsmNote.poiIdFor(placeholderNoteId), fetched.poi.id, fetched.note.noteId)
            deletePoisAndNotes(listOf(OsmNote.poiIdFor(placeholderNoteId)))
            storeNoteFromServer(fetched, completedActionId)
        }

    /** A create osm.org refused for good: the placeholder and its queue row go together. */
    suspend fun dropPlaceholderNote(placeholderNoteId: Long, actionId: Long) = database.withTransaction {
        noteActionDao.delete(actionId)
        deletePoisAndNotes(listOf(OsmNote.poiIdFor(placeholderNoteId)))
    }

    /** The comment / close / reopen requests of one note still waiting to be posted, oldest first. */
    fun observePendingActions(noteId: Long): Flow<List<NoteActionRequest>> = noteActionDao.observeForNote(noteId)

    /** Every queued request, oldest first; the triage list (item 9) marks notes with one on the way. */
    fun observeAllPendingActions(): Flow<List<NoteActionRequest>> = noteActionDao.observeAll()

    fun noteActionDao(): NoteActionDao = noteActionDao

    // Evidence (item 11). No foreign key to `pois`: rows survive the POI's expiry on purpose.

    suspend fun addEvidence(evidence: Evidence): Long = evidenceDao.insert(evidence)

    suspend fun getEvidence(id: Long): Evidence? = evidenceDao.getById(id)

    fun observeEvidenceForPoi(poiId: String): Flow<List<Evidence>> = evidenceDao.observeForPoi(poiId)

    fun observeAllEvidence(): Flow<List<Evidence>> = evidenceDao.observeAll()

    fun observeUnreviewedEvidenceCount(): Flow<Int> = evidenceDao.observeUnreviewedCount()

    suspend fun setEvidenceReviewed(id: Long, reviewed: Boolean) = evidenceDao.setReviewed(id, reviewed)

    /** Removes the row; the caller deletes the photo file (the repository knows nothing about files). */
    suspend fun deleteEvidence(id: Long) = evidenceDao.delete(id)

    /**
     * Store one page of notes fetched around ([lat], [lon]). In one transaction: expire note
     * rows outside the radius, drop in-radius notes the API no longer returns (it returns open
     * notes and ones closed within 7 days, so an absent note is closed or hidden) unless they
     * were closed within [Constants.NOTE_CLOSED_RETENTION_MILLIS], then merge the page in,
     * keeping ping flags on the POI and reclassifying each note. When [truncated] the
     * page is not the whole area and nothing in radius is dropped. Returns how many rows went.
     */
    suspend fun storeFetchedNotes(
        page: List<FetchedNote>,
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        truncated: Boolean,
        now: Long = System.currentTimeMillis()
    ): Int = database.withTransaction {
        val source = OsmNotesService.SOURCE_NOTES
        val expiredOutside = expireOutsideRadius(source, lat, lon, radiusMeters)

        var dropped = 0
        if (!truncated) {
            val returned = page.map { it.poi.id }.toSet()
            val absent = poiDao.getPoisBySource(source).filter { it.id !in returned }
            if (absent.isNotEmpty()) {
                val notesByPoi = absent.map { it.id }
                    .chunked(500)
                    .flatMap { noteDao.getByPoiIds(it) }
                    .associateBy { it.poiId }
                val toDrop = absent.filter { poi ->
                    val note = notesByPoi[poi.id]
                    val closedRecently = note != null && !note.isOpen &&
                        (note.closedAt?.time ?: 0L) > now - Constants.NOTE_CLOSED_RETENTION_MILLIS
                    // A placeholder for a queued create is not on osm.org yet; only the queue drops it.
                    val pending = note != null && note.isPending
                    !closedRecently && !pending
                }.map { it.id }
                deletePoisAndNotes(toDrop)
                dropped = toDrop.size
                if (dropped > 0) Timber.d("Dropped $dropped notes the API no longer returns")
            }
        }

        insertPois(page.map { it.poi })
        insertNotes(page)
        expiredOutside + dropped
    }

    suspend fun setMuted(poiId: String, muted: Boolean) {
        poiDao.setMuted(poiId, muted)
        Timber.d("POI muted=$muted: $poiId")
    }

    suspend fun updatePoi(poi: Poi) = poiDao.updatePoi(poi)

    suspend fun markAsVisited(poiId: String) {
        poiDao.markAsVisited(poiId)
        Timber.d("POI marked as visited: $poiId")
    }

    suspend fun updateLastNotificationTime(poiId: String) {
        poiDao.updateLastNotificationTime(poiId, System.currentTimeMillis())
        Timber.d("Updated notification time for POI: $poiId")
    }

    suspend fun deletePoi(poi: Poi) = poiDao.deletePoi(poi)

    suspend fun deleteAllFromSource(source: String) {
        if (source == OsmNotesService.SOURCE_NOTES) noteDao.deleteAll()
        poiDao.deleteAllFromSource(source)
    }

    /** Delete POI rows and, for notes, their note rows. */
    private suspend fun deletePoisAndNotes(ids: List<String>) {
        ids.chunked(500).forEach {
            noteDao.deleteByPoiIds(it)
            poiDao.deleteByIds(it)
        }
    }

    suspend fun getPoisBySource(source: String): List<Poi> = poiDao.getPoisBySource(source)

    /**
     * Delete rows of [source] farther than [radiusMeters] from the centre. Called after a
     * successful refetch so the stored set follows the walker. Muted or visited rows that
     * fall outside the radius go too; their flags only survive while they stay in range.
     * Returns how many were removed.
     */
    suspend fun expireOutsideRadius(source: String, lat: Double, lon: Double, radiusMeters: Int): Int {
        val candidates = poiDao.getPoisBySource(source)
            .filter { it.distanceTo(lat, lon) > radiusMeters }
            .map { it.id }
        // A placeholder for a queued create (long-pressed far away, say) stays until it is posted.
        val pending = if (source == OsmNotesService.SOURCE_NOTES && candidates.isNotEmpty()) {
            candidates.chunked(500).flatMap { noteDao.getByPoiIds(it) }.filter { it.isPending }.map { it.poiId }.toSet()
        } else emptySet()
        val stale = candidates.filter { it !in pending }
        deletePoisAndNotes(stale)
        if (stale.isNotEmpty()) Timber.d("Expired ${stale.size} $source rows beyond ${radiusMeters}m")
        return stale.size
    }

    suspend fun getStats(): Pair<Int, Int> {
        val active = poiDao.getActivePoiCount()
        val visited = poiDao.getVisitedPoiCount()
        return Pair(active, visited)
    }

    suspend fun importFromGpx(
        inputStream: InputStream,
        sourceName: String = "gpx_import"
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val waypoints = gpxParser.parse(inputStream)
            if (waypoints.isEmpty()) {
                return@withContext Result.failure(Exception("No waypoints found in GPX file"))
            }

            val pois = gpxParser.convertToPois(waypoints, sourceName)
            poiDao.insertPois(pois)

            Timber.d("Imported ${pois.size} POIs from GPX")
            Result.success(pois.size)
        } catch (e: Exception) {
            Timber.e(e, "Failed to import GPX")
            Result.failure(e)
        }
    }

}