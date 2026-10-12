package com.teoilie.ascult.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.teoilie.ascult.BuildConfig
import com.teoilie.ascult.audio.AudioCapture
import com.teoilie.ascult.audio.SILENCE_DBFS
import com.teoilie.ascult.audio.levelDbfs
import com.teoilie.ascult.soniox.SonioxClient
import com.teoilie.ascult.soniox.Transcript
import com.teoilie.ascult.soniox.loadDictionary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long to wait for Soniox to finalise the last tokens after Stop before giving up. */
private const val FINISH_TIMEOUT_MS = 5_000L

data class SessionState(
    val listening: Boolean = false,
    val stopping: Boolean = false,
    val levelDb: Float = SILENCE_DBFS,
    val romanian: Transcript = Transcript(),
    val english: Transcript = Transcript(),
    val error: String? = null,
)

/**
 * Microphone → Soniox → transcripts. Lives for the whole process so it survives the screen going
 * away; TranslationService keeps the process and microphone alive while [SessionState.listening].
 */
class TranslationSession(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val capture = AudioCapture()
    private val soniox = SonioxClient()
    private val stopRequested = MutableStateFlow(false)
    private var sessionJob: Job? = null

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Starts listening; returns false (with [SessionState.error] set) if it cannot. */
    fun start(): Boolean {
        if (sessionJob?.isActive == true) return true
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(error = "Microphone permission is required") }
            return false
        }
        val apiKey = BuildConfig.SONIOX_API_KEY
        if (apiKey.isBlank()) {
            _state.update { it.copy(error = "No Soniox API key: add SONIOX_API_KEY to .env and rebuild") }
            return false
        }

        stopRequested.value = false
        _state.value = SessionState(listening = true)
        sessionJob = scope.launch {
            val dictionary = withContext(Dispatchers.IO) { loadDictionary(context) }
            val audio = capture.frames()
                .takeWhile { !stopRequested.value }
                .onEach { frame -> _state.update { it.copy(levelDb = frame.levelDbfs()) } }

            soniox.transcribe(apiKey, dictionary, audio)
                .catch { e -> _state.update { it.copy(error = e.message ?: e.toString()) } }
                .onCompletion { _state.update { it.copy(listening = false, stopping = false, levelDb = SILENCE_DBFS) } }
                .collect { tokens ->
                    _state.update {
                        it.copy(
                            romanian = it.romanian.update(tokens.filterNot { t -> t.isTranslation }),
                            english = it.english.update(tokens.filter { t -> t.isTranslation }),
                        )
                    }
                }
        }
        return true
    }

    /** Stops the microphone, then lets Soniox finalise the remaining text before the session closes. */
    fun stop() {
        val job = sessionJob ?: return
        if (!job.isActive) return
        stopRequested.value = true
        _state.update { it.copy(stopping = true, levelDb = SILENCE_DBFS) }
        scope.launch {
            delay(FINISH_TIMEOUT_MS)
            job.cancel()
        }
    }
}
