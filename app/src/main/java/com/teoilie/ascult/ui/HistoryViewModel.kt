package com.teoilie.ascult.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teoilie.ascult.app
import com.teoilie.ascult.history.HistoryEntry
import com.teoilie.ascult.settings.EVEN_SPLIT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Shared by the History list and a saved sermon's screen (both in the activity's scope). */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val history = application.app.history
    private val settings = application.app.settings

    val entries: StateFlow<List<HistoryEntry>> = history.entries

    /** Playback is off while listening, so the speaker can't feed the microphone. */
    val listening: StateFlow<Boolean> = application.app.session.state.map { it.listening }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val textScale: StateFlow<Float> = settings.textScale.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

    val paneSplit: StateFlow<Float> = settings.paneSplit.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EVEN_SPLIT)

    fun setPaneSplit(split: Float) {
        viewModelScope.launch { settings.setPaneSplit(split) }
    }

    private var lastDeleted: List<String> = emptyList()
    private val _deletedCount = MutableStateFlow(0)

    /** How many entries the last delete removed while its Undo is offered; 0 when there is none. */
    val deletedCount: StateFlow<Int> = _deletedCount.asStateFlow()

    fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        forgetDeleted() // only the latest delete can be undone
        viewModelScope.launch {
            history.delete(ids)
            lastDeleted = ids.toList()
            _deletedCount.value = ids.size
        }
    }

    fun undoDelete() {
        val ids = lastDeleted
        forgetUndo()
        viewModelScope.launch { history.restore(ids) }
    }

    /** The Undo has gone: remove the deleted entries for good. */
    fun forgetDeleted() {
        val ids = lastDeleted
        if (ids.isEmpty()) return
        forgetUndo()
        history.forget(ids)
    }

    private fun forgetUndo() {
        lastDeleted = emptyList()
        _deletedCount.value = 0
    }

    fun rename(entry: HistoryEntry, title: String) {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty() && trimmed != entry.title) history.write(entry.copy(title = trimmed))
    }

    fun recordingFile(entry: HistoryEntry): File? = history.recordingFile(entry)
}
