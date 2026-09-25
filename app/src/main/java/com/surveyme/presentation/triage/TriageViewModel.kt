package com.surveyme.presentation.triage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.surveyme.core.NoteTriage
import com.surveyme.core.PreferencesManager
import com.surveyme.data.NoteActionQueue
import com.surveyme.data.OsmNotesService
import com.surveyme.data.database.PoiWithNote
import com.surveyme.data.model.NoteActionKind
import com.surveyme.data.model.NoteActionRequest
import com.surveyme.data.model.OsmNote
import com.surveyme.data.model.Poi
import com.surveyme.data.repository.PoiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Date

/**
 * The morning triage list (item 9): open notes created since the last triage, newest first,
 * each with the heuristic guess and the strict tag a tap posts as a comment through
 * [NoteActionQueue].
 *
 * The "new since" stamp is read once, when this screen opens, and the list is filtered against
 * that snapshot so rows do not vanish mid-session. [openedAt] becomes the new stamp on Done and
 * on the first tag posted from here ("leaving after acting" counts as a triage); a skip stamps
 * nothing. A note fetched during the session with a later creation date is new next time.
 */
class TriageViewModel(
    private val repository: PoiRepository,
    private val prefs: PreferencesManager,
    private val queue: NoteActionQueue
) : ViewModel() {

    /** When this screen was opened; what [PreferencesManager.lastTriageAt] becomes on Done. */
    val openedAt: Long = System.currentTimeMillis()

    /** The stamp as it was at open; [NoteTriage.NEVER] before the first Done. */
    val lastTriageAt: Long = prefs.lastTriageAt

    private val _showAll = MutableStateFlow(lastTriageAt == NoteTriage.NEVER)
    val showAll: StateFlow<Boolean> = _showAll.asStateFlow()

    private val skipped = MutableStateFlow<Set<Long>>(emptySet())

    /** Notes whose "Change" was tapped: their buttons show even though they carry a tag. */
    private val changing = MutableStateFlow<Set<Long>>(emptySet())

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private var stamped = false

    init {
        viewModelScope.launch {
            combine(
                repository.getNotesAndFixmesWithNote(),
                repository.observeAllPendingActions(),
                queue.posting,
                combine(_showAll, skipped, changing) { a, b, c -> Triple(a, b, c) }
            ) { rows, pending, posting, (showAll, skip, change) ->
                build(rows, pending, posting, showAll, skip, change)
            }
                .catch { e ->
                    Timber.e(e, "Loading triage list")
                    _state.value = State.Error(e.message ?: "Unknown error")
                }
                .collect { items -> _state.value = State.Ready(items) }
        }
    }

    private fun build(
        rows: List<PoiWithNote>,
        pending: List<NoteActionRequest>,
        posting: Set<Long>,
        showAll: Boolean,
        skip: Set<Long>,
        change: Set<Long>
    ): List<TriageItem> {
        val notes = rows.filter { it.poi.source == OsmNotesService.SOURCE_NOTES && it.note != null }
        val poiByNoteId = notes.associate { it.note!!.noteId to it.poi }
        val selected = NoteTriage.select(notes.map { it.note!! }, lastTriageAt, showAll, skip)
        val pendingComment = pending
            .filter { it.kind == NoteActionKind.COMMENT }
            .groupBy { it.noteId }
            .mapValues { (_, list) -> list.last().text }
        return selected.map { note ->
            TriageItem(
                poi = poiByNoteId.getValue(note.noteId),
                note = note,
                strictTag = NoteTriage.strictTagOf(note.labels),
                guess = NoteTriage.guessFor(note.labels),
                pendingText = pendingComment[note.noteId],
                inFlight = note.noteId in posting,
                changing = note.noteId in change
            )
        }
    }

    fun setShowAll(value: Boolean) {
        _showAll.value = value
    }

    /** Hides the row for this session only; the stamp is untouched. */
    fun skip(noteId: Long) {
        skipped.value = skipped.value + noteId
    }

    fun change(noteId: Long) {
        changing.value = changing.value + noteId
    }

    /**
     * Posts [tag] (with [date] for `#surveyafter`) as a comment through the offline queue. The
     * first post from this session stamps the triage: the user has acted on the list. The
     * caller has checked the login; the queue itself would only hold the row until one.
     */
    fun post(noteId: Long, tag: String, date: Date? = null): String {
        val text = NoteTriage.commentFor(tag, date)
        queue.enqueue(noteId, NoteActionKind.COMMENT, text)
        changing.value = changing.value - noteId
        stamp()
        return text
    }

    /** Done: from now on, "new" means created after this screen was opened. */
    fun done() = stamp()

    private fun stamp() {
        if (stamped) return
        stamped = true
        prefs.lastTriageAt = openedAt
        Timber.d("Triage stamped at $openedAt")
    }

    sealed class State {
        object Loading : State()
        data class Ready(val items: List<TriageItem>) : State()
        data class Error(val message: String) : State()
    }
}

/**
 * One triage row. [strictTag] is the tag the note already carries (buttons hidden unless
 * [changing]); [guess] is the tag the heuristics suggest, pre-highlighted; [pendingText] is a
 * queued comment for this note, shown instead of the buttons until osm.org answers.
 */
data class TriageItem(
    val poi: Poi,
    val note: OsmNote,
    val strictTag: String?,
    val guess: String?,
    val pendingText: String?,
    val inFlight: Boolean,
    val changing: Boolean
)

class TriageViewModelFactory(
    private val repository: PoiRepository,
    private val prefs: PreferencesManager,
    private val queue: NoteActionQueue
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TriageViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TriageViewModel(repository, prefs, queue) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
