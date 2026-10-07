package dev.fitface.studio.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.fitface.studio.core.model.*
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StyleManagementUiState(
    val review: StyleManagement? = null,
    val chosen: Set<String> = emptySet(),
    val protectedVariant: String? = null,
    val confirming: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val deleted: EditorSnapshot? = null,
)

@HiltViewModel
class StyleManagementViewModel @Inject constructor(private val repository: WatchFaceRepository,
    private val diagnostics: DiagnosticsLog) : ViewModel() {
    private val mutable = MutableStateFlow(StyleManagementUiState())
    val state = mutable.asStateFlow()
    private var opened = false

    /** [confirm] goes straight to the confirmation for [initial], as one style's ✕ does. */
    fun open(projectId: Long, protectedVariant: String?, initial: String?, confirm: Boolean = false) {
        if (opened) return
        opened = true
        mutable.value = StyleManagementUiState(protectedVariant = protectedVariant, busy = true)
        viewModelScope.launch {
            try {
                val review = repository.styleManagement()
                require(review.snapshot.projectId == projectId) { "The open project changed. Close and try again." }
                mutable.update { it.copy(review = review, busy = false) }
                if (initial != null) toggle(initial)
                if (confirm) review()
            } catch (error: Exception) { failure(error) }
        }
    }
    fun toggle(name: String) {
        val s = mutable.value
        val names = s.review?.snapshot?.styleNames ?: return
        if (s.busy || s.confirming || name !in names || name == s.protectedVariant) return
        val next = if (name in s.chosen) s.chosen - name else s.chosen + name
        if (next.size >= names.size) return
        mutable.update { it.copy(chosen = next, error = null) }
    }
    fun review() {
        if (!mutable.value.busy && mutable.value.chosen.isNotEmpty()) mutable.update { it.copy(confirming = true, error = null) }
    }
    fun back() { if (!mutable.value.busy) mutable.update { it.copy(confirming = false) } }
    fun delete() {
        val s = mutable.value
        val review = s.review ?: return
        if (s.busy || !s.confirming || s.chosen.isEmpty() || s.protectedVariant in s.chosen) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val result = repository.deleteStyles(s.chosen, review.revision)
                mutable.update { it.copy(busy = false, deleted = result) }
            } catch (error: Exception) { failure(error) }
        }
    }
    fun close() { if (!mutable.value.busy) { opened = false; mutable.value = StyleManagementUiState() } }
    private fun failure(error: Exception) {
        if (error is CancellationException) throw error
        diagnostics.warn("StyleManagement", "Style deletion failed", (error as? WatchFaceException)?.technicalDetail, error)
        mutable.update { it.copy(busy = false, error = (error as? WatchFaceException)?.userMessage ?: error.message) }
    }
}
