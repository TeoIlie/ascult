package com.teoilie.ascult.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.teoilie.ascult.session
import com.teoilie.ascult.session.SessionState
import com.teoilie.ascult.session.TranslationService
import kotlinx.coroutines.flow.StateFlow

class TranslationViewModel(application: Application) : AndroidViewModel(application) {
    private val session = application.session

    val state: StateFlow<SessionState> = session.state

    fun start() {
        // Start the session while the app is in the foreground (required for microphone access),
        // then the service to keep it running when the screen goes off.
        if (session.start()) TranslationService.start(getApplication())
    }

    fun stop() = session.stop()
}
