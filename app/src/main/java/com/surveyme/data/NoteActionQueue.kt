package com.surveyme.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.surveyme.data.model.NoteActionKind
import com.surveyme.data.model.NoteActionRequest
import com.surveyme.data.model.NoteStatus
import com.surveyme.data.repository.PoiRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The offline queue of comment / close / reopen requests (roadmap item 6).
 *
 * [enqueue] writes a `note_actions` row, applies the optimistic status to the note (close and
 * reopen only; a comment shows as a pending thread entry rendered from the row itself) and
 * kicks a drain. A drain posts rows oldest first, one at a time, so two actions on one note
 * keep their order. Each row is settled by osm.org's answer:
 *
 * - 2xx: the answer is the whole note; it replaces the stored thread and status and the row
 *   goes, in one transaction.
 * - network trouble, 429 or 5xx (soft): the row stays with `attempts` and `lastError` bumped,
 *   the drain stops and retries after a backoff (30 s doubling to 10 min, or Retry-After).
 *   Connectivity coming back resets the backoff.
 * - any other 4xx (hard; 409 "already closed", 404, 400): the row goes, the note is refreshed
 *   from osm.org (or its status flipped back when even that fails) and the server's text is
 *   shown in a toast. Later rows of the same note still get their turn.
 * - 401: hard failure, plus the login is cleared through [OsmAuthManager.logout] and the drain
 *   stops. Rows left in the queue wait for the next login; nothing is dropped silently.
 *
 * Drains run on app start, when a network with internet appears, after a login, after each
 * enqueue, and after each successful post (the loop simply continues). No WorkManager: it is
 * not a dependency, and the process being alive is the realistic case for a walker.
 */
class NoteActionQueue private constructor(context: Context) {

    companion object {
        private const val BACKOFF_BASE_MILLIS = 30 * 1000L
        private const val BACKOFF_MAX_MILLIS = 10 * 60 * 1000L

        @Volatile
        private var instance: NoteActionQueue? = null

        fun get(context: Context): NoteActionQueue =
            instance ?: synchronized(this) {
                instance ?: NoteActionQueue(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val repository: PoiRepository by lazy { PoiManager.getRepository(appContext) }
    private val dao by lazy { repository.noteActionDao() }
    // Lazy: opening the encrypted prefs is keystore work, better off the main thread at app start.
    private val auth: OsmAuthManager by lazy { OsmAuthManager.get(appContext) }
    private val service = OsmNotesService()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Timber.e(e, "Note action queue failed")
        }
    )
    private val running = Mutex()
    private val started = AtomicBoolean(false)
    private var retryJob: Job? = null
    private var backoffMillis = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _posting = MutableStateFlow<Set<Long>>(emptySet())

    /** Note ids with a request on the wire right now (not merely queued): their buttons are disabled. */
    val posting: StateFlow<Set<Long>> = _posting

    /** Registers the drain triggers and drains once. Idempotent; called from the Application. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        registerConnectivity()
        scope.launch {
            auth.state.collect { state ->
                if (state is OsmAuthState.LoggedIn) drain("login")
            }
        }
        drain("app start")
    }

    /**
     * Queues [kind] with [text] for note [noteId] and starts posting. Returns at once; the
     * sheet sees the row through [PoiRepository.observePendingActions].
     */
    fun enqueue(noteId: Long, kind: NoteActionKind, text: String) {
        scope.launch {
            dao.insert(NoteActionRequest(noteId = noteId, action = kind.apiValue, text = text.trim()))
            kind.resultingStatus?.let { repository.setNoteStatusLocally(noteId, it) }
            Timber.d("Queued ${kind.apiValue} for note $noteId")
            drainNow("enqueue")
        }
    }

    /** Posts whatever is queued, unless a drain is already running. */
    fun drain(reason: String) {
        retryJob?.cancel()
        retryJob = null
        scope.launch { drainNow(reason) }
    }

    private suspend fun drainNow(reason: String) {
        if (!running.tryLock()) return
        try {
            while (true) {
                val row = dao.getAllOrdered().firstOrNull() ?: return
                val header = auth.authHeader()
                if (header == null) {
                    Timber.d("Queue ($reason): ${dao.count()} rows wait for a login")
                    return
                }
                _posting.value = _posting.value + row.noteId
                try {
                    val fetched = service.postNoteAction(row.noteId, row.kind, row.text, header)
                    repository.storeNoteFromServer(fetched, completedActionId = row.id)
                    backoffMillis = 0L
                    Timber.i("Posted ${row.action} to note ${row.noteId}")
                } catch (e: FetchHttpException) {
                    when {
                        e.code == 401 -> {
                            hardFail(row, "OSM rejected the login; log in again")
                            auth.logout()
                            return
                        }
                        e.code == 429 || e.code >= 500 -> {
                            softFail(row, e.message ?: "HTTP ${e.code}", e.retryAfterSeconds?.times(1000))
                            return
                        }
                        else -> hardFail(row, e.body ?: "osm.org answered HTTP ${e.code}")
                    }
                } catch (e: IOException) {
                    softFail(row, e.message ?: "network error", null)
                    return
                } catch (e: Exception) {
                    Timber.e(e, "Unexpected failure posting ${row.action} to note ${row.noteId}")
                    hardFail(row, e.message ?: e.javaClass.simpleName)
                } finally {
                    _posting.value = _posting.value - row.noteId
                }
            }
        } finally {
            running.unlock()
        }
    }

    private suspend fun softFail(row: NoteActionRequest, message: String, retryAfterMillis: Long?) {
        dao.markAttempt(row.id, row.attempts + 1, message)
        val doubled = if (backoffMillis == 0L) BACKOFF_BASE_MILLIS else backoffMillis * 2
        backoffMillis = maxOf(doubled, retryAfterMillis ?: 0L).coerceAtMost(BACKOFF_MAX_MILLIS)
        Timber.w("${row.action} on note ${row.noteId} failed ($message), retry in ${backoffMillis / 1000}s")
        val wait = backoffMillis
        retryJob?.cancel()
        // The timer only owns the sleep: a drain triggered meanwhile (network back, login) cancels
        // the sleep, never a post that is already on the wire.
        retryJob = scope.launch {
            delay(wait)
            retryJob = null
            scope.launch { drainNow("retry") }
        }
    }

    /**
     * osm.org refused for good: drop the row, put the note back to what the server says (a
     * refresh, or a status flip when offline) and tell the user why.
     */
    private suspend fun hardFail(row: NoteActionRequest, message: String) {
        Timber.w("${row.action} on note ${row.noteId} refused: $message")
        dao.delete(row.id)
        val refreshed = runCatching { service.fetchNote(row.noteId) }
            .onFailure { Timber.w(it, "Could not refresh note ${row.noteId} after a refused ${row.action}") }
            .getOrNull()
        if (refreshed != null) {
            repository.storeNoteFromServer(refreshed)
        } else {
            row.kind.resultingStatus?.let { optimistic ->
                val stillPending = dao.getForNote(row.noteId).any { it.kind.resultingStatus != null }
                if (!stillPending) {
                    val back = if (optimistic == NoteStatus.CLOSED) NoteStatus.OPEN else NoteStatus.CLOSED
                    repository.setNoteStatusLocally(row.noteId, back)
                }
            }
        }
        val verb = when (row.kind) {
            NoteActionKind.COMMENT -> "Comment"
            NoteActionKind.CLOSE -> "Close"
            NoteActionKind.REOPEN -> "Reopen"
        }
        toast("$verb on note ${row.noteId} refused: $message")
    }

    private fun toast(text: String) {
        mainHandler.post { Toast.makeText(appContext, text, Toast.LENGTH_LONG).show() }
    }

    /** A network with internet showing up (also fires once at registration) drains the queue. */
    private fun registerConnectivity() {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            manager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    backoffMillis = 0L
                    drain("network available")
                }
            })
        }.onFailure { Timber.w(it, "No connectivity callback; the queue drains on start and after actions only") }
    }
}
