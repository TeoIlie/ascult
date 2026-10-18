package com.teoilie.ascult.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teoilie.ascult.session.SessionState
import com.teoilie.ascult.session.TranslationService
import com.teoilie.ascult.BuildConfig
import com.teoilie.ascult.app
import com.teoilie.ascult.settings.AudioSettings
import com.teoilie.ascult.settings.TEXT_SCALES
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TranslationViewModel(application: Application) : AndroidViewModel(application) {
    private val session = application.app.session
    private val settings = application.app.settings

    val state: StateFlow<SessionState> = session.state
    val audioSettings: StateFlow<AudioSettings> =
        settings.audio.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudioSettings())

    /** Whether Claude translation has a key: one set in Settings, or the debug build's. */
    val claudeKeySet: StateFlow<Boolean> = settings.anthropicApiKey
        .map { it.isNotBlank() || BuildConfig.ANTHROPIC_API_KEY.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val textScale: StateFlow<Float> = settings.textScale.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

    val paneSplit: StateFlow<Float> = settings.paneSplit.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.5f)

    fun setPaneSplit(split: Float) {
        viewModelScope.launch { settings.setPaneSplit(split) }
    }

    /** Moves the text size [steps] along [TEXT_SCALES]. */
    fun changeTextScale(steps: Int) {
        val current = TEXT_SCALES.indexOfFirst { it >= textScale.value }.takeIf { it >= 0 } ?: TEXT_SCALES.lastIndex
        val next = TEXT_SCALES[(current + steps).coerceIn(0, TEXT_SCALES.lastIndex)]
        viewModelScope.launch { settings.setTextScale(next) }
    }

    fun start() {
        // Start the session while the app is in the foreground (required for microphone access),
        // then the service to keep it running when the screen goes off.
        viewModelScope.launch {
            if (session.start()) TranslationService.start(getApplication())
        }
    }

    fun stop() = session.stop()

    fun clear() = session.clear()

    fun updateAudio(transform: (AudioSettings) -> AudioSettings) {
        viewModelScope.launch { settings.updateAudio(transform) }
    }
}
