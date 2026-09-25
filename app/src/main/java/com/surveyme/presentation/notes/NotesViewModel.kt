package com.surveyme.presentation.notes

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.surveyme.data.model.OsmNote
import com.surveyme.data.model.Poi
import com.surveyme.data.repository.PoiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber

class NotesViewModel(
    private val repository: PoiRepository
) : ViewModel() {

    private val _notesState = MutableStateFlow<NotesState>(NotesState.Loading)
    val notesState: StateFlow<NotesState> = _notesState.asStateFlow()

    private var currentUserLocation: Location? = null

    init {
        loadNotes()
    }

    private fun loadNotes() {
        viewModelScope.launch {
            _notesState.value = NotesState.Loading
            repository.getNotesAndFixmesWithNote()
                .catch { e ->
                    Timber.e(e, "Error loading OSM Notes")
                    _notesState.value = NotesState.Error(e.message ?: "Unknown error")
                }
                .collect { rows ->
                    val noteItems = rows.map { row ->
                        val poi = row.poi
                        val distance = currentUserLocation?.let { loc ->
                            val results = FloatArray(1)
                            Location.distanceBetween(
                                loc.latitude, loc.longitude,
                                poi.latitude, poi.longitude,
                                results
                            )
                            results[0]
                        }
                        NoteItem(poi, distance, row.note)
                    }.sortedBy { it.distance ?: Float.MAX_VALUE }
                    
                    if (noteItems.isEmpty()) {
                        _notesState.value = NotesState.Empty
                    } else {
                        _notesState.value = NotesState.Success(noteItems)
                    }
                }
        }
    }

    fun updateLocation(location: Location) {
        currentUserLocation = location
        // Re-sorting the current list based on new location
        if (_notesState.value is NotesState.Success) {
            val currentList = (_notesState.value as NotesState.Success).notes
            val updatedList = currentList.map { item ->
                val results = FloatArray(1)
                Location.distanceBetween(
                    location.latitude, location.longitude,
                    item.poi.latitude, item.poi.longitude,
                    results
                )
                item.copy(distance = results[0])
            }.sortedBy { it.distance ?: Float.MAX_VALUE }
            _notesState.value = NotesState.Success(updatedList)
        }
    }

    sealed class NotesState {
        object Loading : NotesState()
        object Empty : NotesState()
        data class Success(val notes: List<NoteItem>) : NotesState()
        data class Error(val message: String) : NotesState()
    }
}

/** A Notes tab row. [note] is the structured note for OSM notes, null for fixmes and pre-v4 rows. */
data class NoteItem(
    val poi: Poi,
    val distance: Float?,
    val note: OsmNote? = null
)

class NotesViewModelFactory(
    private val repository: PoiRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NotesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NotesViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
