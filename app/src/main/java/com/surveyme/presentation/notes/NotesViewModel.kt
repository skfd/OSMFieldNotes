package com.surveyme.presentation.notes

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.surveyme.core.FixmeMaxAge
import com.surveyme.core.NoteLabels
import com.surveyme.core.NotePingPolicy
import com.surveyme.core.PreferencesManager
import com.surveyme.data.OverpassService
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
 * The Notes tab list: every stored note and fixme, filtered by the audience chip and the fixme
 * age setting (item 12; fixmes on relations never show), sorted with the notes that would ping
 * right now first, then everything else by distance, and dismissed fixmes at the bottom.
 */
class NotesViewModel(
    private val repository: PoiRepository,
    private val prefs: PreferencesManager
) : ViewModel() {

    private val _notesState = MutableStateFlow<NotesState>(NotesState.Loading)
    val notesState: StateFlow<NotesState> = _notesState.asStateFlow()

    /** [PreferencesManager.AUDIENCE_FILTER_ALL] or one of [NoteLabels.AUDIENCES]; seeded from prefs. */
    private val _filter = MutableStateFlow(prefs.notesAudienceFilter)
    val filter: StateFlow<String> = _filter.asStateFlow()

    /** The fixme age setting (item 12); seeded from prefs, changed from the tab's overflow menu. */
    private val _fixmeMaxAge = MutableStateFlow(prefs.fixmeMaxAge)
    val fixmeMaxAge: StateFlow<FixmeMaxAge> = _fixmeMaxAge.asStateFlow()

    private val location = MutableStateFlow<Location?>(null)

    private data class Inputs(val rows: List<com.surveyme.data.database.PoiWithNote>, val filter: String, val location: Location?, val maxAge: FixmeMaxAge)

    init {
        loadNotes()
    }

    private fun loadNotes() {
        viewModelScope.launch {
            _notesState.value = NotesState.Loading
            combine(repository.getNotesAndFixmesWithNote(), _filter, location, _fixmeMaxAge) { rows, filter, loc, maxAge ->
                Inputs(rows, filter, loc, maxAge)
            }
                .catch { e ->
                    Timber.e(e, "Error loading OSM Notes")
                    _notesState.value = NotesState.Error(e.message ?: "Unknown error")
                }
                .collect { (rows, filter, loc, maxAge) ->
                    val now = Date()
                    val all = rows
                        // Item 12: a fixme on a relation is not walkable and one older than the
                        // setting is not worth the walk; both stay stored (the setting may change).
                        .filter { row -> !row.poi.isOnRelation && (!row.poi.isFixme || maxAge.allows(row.poi.lastEditedAt, now)) }
                        .map { row ->
                            val poi = row.poi
                            val distance = loc?.let { poi.distanceTo(it.latitude, it.longitude) }
                            NoteItem(
                                poi = poi,
                                distance = distance,
                                note = row.note,
                                pingable = row.note != null && NotePingPolicy.mayPing(row.note, now)
                            )
                        }
                    // Pinging notes first, dismissed fixmes last, distance within each group.
                    val shown = all.filter { it.matches(filter) }
                        .sortedWith(compareBy({ it.sortGroup }, { it.distance ?: Float.MAX_VALUE }))
                    // An empty store is a different message from "the filters hid everything".
                    _notesState.value = when {
                        rows.isEmpty() -> NotesState.Empty(filtered = false)
                        shown.isEmpty() -> NotesState.Empty(filtered = true)
                        else -> NotesState.Success(shown)
                    }
                }
        }
    }

    fun updateLocation(location: Location) {
        this.location.value = location
    }

    fun setFilter(filter: String) {
        if (_filter.value == filter) return
        _filter.value = filter
        prefs.notesAudienceFilter = filter
    }

    fun setFixmeMaxAge(age: FixmeMaxAge) {
        if (_fixmeMaxAge.value == age) return
        _fixmeMaxAge.value = age
        prefs.fixmeMaxAge = age
    }

    /**
     * Dismiss a fixme for now, or take that back (item 12's swipe; the same `isMuted` flag the
     * details sheet's chip sets and the tracking service filters on). The list re-sorts itself
     * from the database emission.
     */
    fun setFixmeDismissed(poiId: String, dismissed: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setMuted(poiId, dismissed) }
                .onFailure { Timber.e(it, "Dismissing fixme $poiId") }
        }
    }

    sealed class NotesState {
        object Loading : NotesState()
        /** Nothing to show; [filtered] when the store has rows but the chip or the fixme age hides them all. */
        data class Empty(val filtered: Boolean) : NotesState()
        data class Success(val notes: List<NoteItem>) : NotesState()
        data class Error(val message: String) : NotesState()
    }
}

/**
 * A Notes tab row. [note] is the structured note for OSM notes, null for fixmes and pre-v4
 * rows; [pingable] is whether [NotePingPolicy] would let this note ping now (always false for
 * a row without a note, fixmes sort with the rest).
 */
data class NoteItem(
    val poi: Poi,
    val distance: Float?,
    val note: OsmNote? = null,
    val pingable: Boolean = false
) {
    /** 0 for a note that pings now, 2 for a dismissed fixme (bottom of the list), 1 for the rest. */
    val sortGroup: Int
        get() = when {
            pingable -> 0
            poi.isDismissedFixme -> 2
            else -> 1
        }

    /**
     * Whether the audience chip shows this row. Notes match on their labels; a fixme's labels
     * (item 12) say what to check, not who, and it needs a visit by definition, so it shows
     * under All and Surveyor.
     */
    fun matches(filter: String): Boolean {
        if (filter == PreferencesManager.AUDIENCE_FILTER_ALL) return true
        val note = note
        if (note != null) return filter in note.labels
        return poi.source == OverpassService.SOURCE_FIXME && filter == NoteLabels.SURVEYOR
    }
}

class NotesViewModelFactory(
    private val repository: PoiRepository,
    private val prefs: PreferencesManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NotesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NotesViewModel(repository, prefs) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
