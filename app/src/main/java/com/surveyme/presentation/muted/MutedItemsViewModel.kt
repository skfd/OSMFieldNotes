package com.surveyme.presentation.muted

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.surveyme.data.database.PoiWithNote
import com.surveyme.data.repository.PoiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The muted-items list (item 13): every note or fixme the user muted from a ping, dismissed
 * from its details or swiped away, newest first. Observed, so an Unmute re-renders the list
 * and the row goes.
 */
class MutedItemsViewModel(private val repository: PoiRepository) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeMutedItems()
                .catch { e ->
                    Timber.e(e, "Loading muted items")
                    _state.value = State.Error(e.message ?: "Unknown error")
                }
                .collect { _state.value = State.Ready(it) }
        }
    }

    fun setMuted(poiId: String, muted: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setMuted(poiId, muted) }
                .onFailure { Timber.e(it, "Setting muted=$muted on $poiId") }
        }
    }

    fun unmuteAll(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.unmuteAll() }
                .onSuccess(onDone)
                .onFailure { Timber.e(it, "Unmuting all") }
        }
    }

    sealed class State {
        object Loading : State()
        data class Ready(val items: List<PoiWithNote>) : State()
        data class Error(val message: String) : State()
    }
}

class MutedItemsViewModelFactory(private val repository: PoiRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MutedItemsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MutedItemsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
