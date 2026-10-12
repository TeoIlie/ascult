package com.teoilie.ascult.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teoilie.ascult.session
import com.teoilie.ascult.session.SessionState
import com.teoilie.ascult.session.TranslationService
import com.teoilie.ascult.settings
import com.teoilie.ascult.settings.AudioSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TranslationViewModel(application: Application) : AndroidViewModel(application) {
    private val session = application.session
    private val settings = application.settings

    val state: StateFlow<SessionState> = session.state
    val audioSettings: StateFlow<AudioSettings> =
        settings.audio.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudioSettings())

    fun start() {
        // Start the session while the app is in the foreground (required for microphone access),
        // then the service to keep it running when the screen goes off.
        if (session.start()) TranslationService.start(getApplication())
    }

    fun stop() = session.stop()

    fun updateAudio(transform: (AudioSettings) -> AudioSettings) {
        viewModelScope.launch { settings.updateAudio(transform) }
    }
}
