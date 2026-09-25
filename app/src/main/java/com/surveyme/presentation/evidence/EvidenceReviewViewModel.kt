package com.surveyme.presentation.evidence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.surveyme.data.EvidenceStore
import com.surveyme.data.model.Evidence
import com.surveyme.data.model.Poi
import com.surveyme.data.repository.PoiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The "to review" list (item 11): every piece of evidence newest first, unreviewed only unless
 * [showReviewed] is on. Rows are observed, so a Reviewed or Delete from a row re-renders.
 */
class EvidenceReviewViewModel(private val repository: PoiRepository) : ViewModel() {

    private val _showReviewed = MutableStateFlow(false)
    val showReviewed: StateFlow<Boolean> = _showReviewed.asStateFlow()

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.observeAllEvidence(), _showReviewed) { all, showReviewed ->
                val unreviewed = all.count { !it.reviewed }
                State.Ready(
                    items = if (showReviewed) all else all.filter { !it.reviewed },
                    total = all.size,
                    unreviewed = unreviewed
                )
            }
                .catch { e ->
                    Timber.e(e, "Loading evidence list")
                    _state.value = State.Error(e.message ?: "Unknown error")
                }
                .collect { _state.value = it }
        }
    }

    fun setShowReviewed(value: Boolean) {
        _showReviewed.value = value
    }

    fun setReviewed(evidence: Evidence, reviewed: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setEvidenceReviewed(evidence.id, reviewed) }
                .onFailure { Timber.e(it, "Marking evidence ${evidence.id} reviewed=$reviewed") }
        }
    }

    /** The row goes first, then the file: a crash in between leaves at worst an orphan file, never a row without its photo. */
    fun delete(evidence: Evidence) {
        viewModelScope.launch {
            runCatching {
                repository.deleteEvidence(evidence.id)
                EvidenceStore.deleteFile(evidence.photoPath)
            }.onFailure { Timber.e(it, "Deleting evidence ${evidence.id}") }
        }
    }

    /** The POI the evidence was pinned to, or null once it expired (the snapshot stands in). */
    suspend fun findPoi(evidence: Evidence): Poi? =
        runCatching { repository.getPoiById(evidence.poiId) }
            .onFailure { Timber.e(it, "Loading POI ${evidence.poiId} for evidence") }
            .getOrNull()

    sealed class State {
        object Loading : State()
        data class Ready(val items: List<Evidence>, val total: Int, val unreviewed: Int) : State()
        data class Error(val message: String) : State()
    }
}

class EvidenceReviewViewModelFactory(private val repository: PoiRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EvidenceReviewViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return EvidenceReviewViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
