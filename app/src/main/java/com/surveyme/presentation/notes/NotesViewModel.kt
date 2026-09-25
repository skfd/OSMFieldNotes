package com.surveyme.presentation.notes

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
 * The Notes tab list: every stored note and fixme, filtered by the audience chip and sorted
 * with the notes that would ping right now first, then by distance (item 8).
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

    private val location = MutableStateFlow<Location?>(null)

    init {
        loadNotes()
    }

    private fun loadNotes() {
        viewModelScope.launch {
            _notesState.value = NotesState.Loading
            combine(repository.getNotesAndFixmesWithNote(), _filter, location) { rows, filter, loc ->
                Triple(rows, filter, loc)
            }
                .catch { e ->
                    Timber.e(e, "Error loading OSM Notes")
                    _notesState.value = NotesState.Error(e.message ?: "Unknown error")
                }
                .collect { (rows, filter, loc) ->
                    val now = Date()
                    val all = rows.map { row ->
                        val poi = row.poi
                        val distance = loc?.let { poi.distanceTo(it.latitude, it.longitude) }
                        NoteItem(
                            poi = poi,
                            distance = distance,
                            note = row.note,
                            pingable = row.note != null && NotePingPolicy.mayPing(row.note, now)
                        )
                    }
                    val shown = all.filter { it.matches(filter) }
                        .sortedWith(compareBy({ if (it.pingable) 0 else 1 }, { it.distance ?: Float.MAX_VALUE }))
                    _notesState.value = when {
                        all.isEmpty() -> NotesState.Empty(filtered = false)
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

    sealed class NotesState {
        object Loading : NotesState()
        /** Nothing to show; [filtered] when the store has rows but the audience chip hides them all. */
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
    /**
     * Whether the audience chip shows this row. Notes match on their labels; a fixme has no
     * labels until item 12 and needs a visit by definition, so it shows under All and Surveyor.
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
