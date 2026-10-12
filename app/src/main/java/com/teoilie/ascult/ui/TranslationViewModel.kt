package com.teoilie.ascult.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teoilie.ascult.audio.AudioCapture
import com.teoilie.ascult.audio.SILENCE_DBFS
import com.teoilie.ascult.audio.levelDbfs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val listening: Boolean = false,
    val levelDb: Float = SILENCE_DBFS,
    val error: String? = null,
)

class TranslationViewModel(application: Application) : AndroidViewModel(application) {
    private val capture = AudioCapture()
    private var captureJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun start() {
        if (captureJob?.isActive == true) return
        val permission = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(error = "Microphone permission is required") }
            return
        }

        _state.update { it.copy(listening = true, error = null) }
        captureJob = viewModelScope.launch {
            capture.frames()
                .catch { e -> _state.update { it.copy(error = e.message) } }
                .onCompletion { _state.update { it.copy(listening = false, levelDb = SILENCE_DBFS) } }
                .collect { frame -> _state.update { it.copy(levelDb = frame.levelDbfs()) } }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
    }
}
