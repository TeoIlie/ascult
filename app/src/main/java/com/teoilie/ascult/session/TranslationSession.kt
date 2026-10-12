package com.teoilie.ascult.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.teoilie.ascult.BuildConfig
import com.teoilie.ascult.audio.AudioCapture
import com.teoilie.ascult.audio.SILENCE_DBFS
import com.teoilie.ascult.audio.levelDbfs
import com.teoilie.ascult.audio.recordTo
import com.teoilie.ascult.audio.withGain
import com.teoilie.ascult.settings.AudioSettings
import com.teoilie.ascult.settings.SettingsStore
import com.teoilie.ascult.soniox.Segment
import com.teoilie.ascult.soniox.SonioxClient
import com.teoilie.ascult.soniox.loadDictionary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How long to wait for Soniox to finalise the last tokens after Stop before giving up. */
private const val FINISH_TIMEOUT_MS = 5_000L

data class SessionState(
    val listening: Boolean = false,
    val stopping: Boolean = false,
    val levelDb: Float = SILENCE_DBFS,
    /** Runs since the last Clear; the last one is the current (or most recent) session. */
    val segments: List<Segment> = emptyList(),
    val error: String? = null,
    /** WAV file of the last session's audio, if recording was on. */
    val recording: File? = null,
)

/**
 * Microphone → Soniox → transcripts. Lives for the whole process so it survives the screen going
 * away; TranslationService keeps the process and microphone alive while [SessionState.listening].
 */
class TranslationSession(private val context: Context, settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val soniox = SonioxClient()
    private val audioSettings = settings.audio.stateIn(scope, SharingStarted.Eagerly, AudioSettings())
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

        // Source and recording are fixed for the session; gain is read per frame so it can be adjusted live.
        val settings = audioSettings.value
        val recording = if (settings.saveRecording) recordingFile(settings) else null
        val label = "${settings.source.label} · +${settings.gainDb} dB · ${SimpleDateFormat("HH:mm", Locale.US).format(Date())}"
        stopRequested.value = false
        _state.update { SessionState(listening = true, recording = recording, segments = it.segments + Segment(label)) }
        sessionJob = scope.launch {
            val dictionary = withContext(Dispatchers.IO) { loadDictionary(context) }
            val audio = AudioCapture(settings.source.audioSource).frames()
                .map { it.withGain(audioSettings.value.gainDb) }
                .let { frames -> if (recording != null) frames.recordTo(recording) else frames }
                .flowOn(Dispatchers.IO)
                .takeWhile { !stopRequested.value }
                .onEach { frame -> _state.update { it.copy(levelDb = frame.levelDbfs()) } }

            soniox.transcribe(apiKey, dictionary, audio)
                .catch { e -> _state.update { it.copy(error = e.message ?: e.toString()) } }
                .onCompletion { _state.update { it.copy(listening = false, stopping = false, levelDb = SILENCE_DBFS) } }
                .collect { tokens ->
                    _state.update {
                        val current = it.segments.last()
                        val updated = current.copy(
                            romanian = current.romanian.update(tokens.filterNot { t -> t.isTranslation }),
                            english = current.english.update(tokens.filter { t -> t.isTranslation }),
                        )
                        it.copy(segments = it.segments.dropLast(1) + updated)
                    }
                }
        }
        return true
    }

    /** Forgets all text; only between sessions. */
    fun clear() {
        if (sessionJob?.isActive == true) return
        _state.value = SessionState()
    }

    /** e.g. recordings/20261012-191500_UNPROCESSED_+12dB.wav in the app's external files directory. */
    private fun recordingFile(settings: AudioSettings): File {
        val dir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "${time}_${settings.source.name}_+${settings.gainDb}dB.wav")
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
