package com.teoilie.ascult.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.teoilie.ascult.BuildConfig
import com.teoilie.ascult.audio.AudioCapture
import com.teoilie.ascult.audio.SILENCE_DBFS
import com.teoilie.ascult.audio.levelDbfs
import com.teoilie.ascult.audio.recordTo
import com.teoilie.ascult.audio.withGain
import com.teoilie.ascult.settings.AudioSettings
import com.teoilie.ascult.settings.SettingsStore
import com.teoilie.ascult.soniox.Dictionary
import com.teoilie.ascult.soniox.Segment
import com.teoilie.ascult.soniox.SonioxClient
import com.teoilie.ascult.soniox.SonioxException
import com.teoilie.ascult.soniox.Token
import com.teoilie.ascult.soniox.Transcript
import com.teoilie.ascult.soniox.toPlainText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How long to wait for Soniox to finalise the last tokens after Stop before giving up. */
private const val FINISH_TIMEOUT_MS = 5_000L

/** Audio kept while reconnecting (sent once the connection is back); older frames are dropped. */
private const val RECONNECT_BUFFER_FRAMES = 300 // 30 s

private const val MAX_RECONNECT_DELAY_MS = 10_000L

/** How often the transcript is saved while listening, so little is lost if the process is killed. */
private const val AUTOSAVE_INTERVAL_MS = 15_000L

enum class Phase {
    IDLE,
    LISTENING,
    /** Still capturing (into the buffer) while the connection to Soniox is re-established. */
    RECONNECTING,
    /** Microphone off; waiting for Soniox to finalise the last tokens. */
    STOPPING,
}

data class SessionState(
    val phase: Phase = Phase.IDLE,
    val levelDb: Float = SILENCE_DBFS,
    /** Runs since the last Clear; the last one is the current (or most recent) session. */
    val segments: List<Segment> = emptyList(),
    val error: String? = null,
    /** WAV file of the last session's audio, if recording was on. */
    val recording: File? = null,
) {
    val listening: Boolean get() = phase != Phase.IDLE
}

/**
 * Microphone → Soniox → transcripts. Lives for the whole process so it survives the screen going
 * away; TranslationService keeps the process and microphone alive while [SessionState.listening].
 */
class TranslationSession(private val context: Context, private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val soniox = SonioxClient()
    /** Kept current so gain changes apply to a running session. */
    private val audioSettings = settings.audio.stateIn(scope, SharingStarted.Eagerly, AudioSettings())
    private val stopRequested = MutableStateFlow(false)
    private var sessionJob: Job? = null

    /** Serialises transcript writes so an autosave and the final save never interleave. */
    private val fileWriter = Dispatchers.IO.limitedParallelism(1)

    /** Where the transcript since the last Clear is saved; named when its first session starts. */
    private var transcriptFile: File? = null

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Starts listening; returns false (with [SessionState.error] set) if it cannot. */
    suspend fun start(): Boolean {
        if (sessionJob?.isActive == true) return true
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(error = "Microphone permission is required") }
            return false
        }
        val apiKey = settings.apiKey.first().ifBlank { BuildConfig.SONIOX_API_KEY }
        if (apiKey.isBlank()) {
            _state.update { it.copy(error = "No Soniox API key: add one in Settings") }
            return false
        }

        // Source and recording are fixed for the session; gain is read per frame so it can be adjusted live.
        val audioSettings = settings.audio.first()
        val recording = if (audioSettings.saveRecording) {
            newFile("recordings", "_${audioSettings.source.name}_+${audioSettings.gainDb}dB.wav")
        } else {
            null
        }
        if (transcriptFile == null) transcriptFile = newFile("transcripts", ".txt")
        val label = "${audioSettings.source.label} · +${audioSettings.gainDb} dB · ${timestamp("HH:mm")}"

        stopRequested.value = false
        _state.update { SessionState(Phase.LISTENING, recording = recording, segments = it.segments + Segment(label)) }
        sessionJob = scope.launch {
            try {
                listen(apiKey, audioSettings, recording)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(phase = Phase.IDLE, levelDb = SILENCE_DBFS) }
                saveTranscript()
            }
        }
        return true
    }

    /**
     * Captures into a buffer that outlives individual connections, and (re)connects to Soniox until
     * the audio ends. A dropped connection loses only that connection's provisional text.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun listen(apiKey: String, audioSettings: AudioSettings, recording: File?) = coroutineScope {
        val dictionary = settings.dictionary.first() ?: withContext(Dispatchers.IO) { Dictionary.default(context) }
        val audio = Channel<ShortArray>(RECONNECT_BUFFER_FRAMES, BufferOverflow.DROP_OLDEST)
        val capture = launch {
            try {
                AudioCapture(audioSettings.source.audioSource).frames()
                    .map { it.withGain(this@TranslationSession.audioSettings.value.gainDb) }
                    .let { frames -> if (recording != null) frames.recordTo(recording) else frames }
                    .flowOn(Dispatchers.IO)
                    .takeWhile { !stopRequested.value }
                    .collect { frame ->
                        _state.update { it.copy(levelDb = frame.levelDbfs()) }
                        audio.send(frame)
                    }
                audio.close() // Stop: the connection sends end-of-audio once the buffer drains
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                audio.close(e) // microphone failure ends the session
            }
        }

        val autosave = launch {
            while (true) {
                delay(AUTOSAVE_INTERVAL_MS)
                saveTranscript()
            }
        }

        var attempt = 0
        while (true) {
            try {
                soniox.transcribe(apiKey, dictionary, audio.receiveAsFlow()).collect { tokens ->
                    attempt = 0
                    _state.update {
                        val phase = if (it.phase == Phase.RECONNECTING) Phase.LISTENING else it.phase
                        it.copy(phase = phase, error = null).withTokens(tokens)
                    }
                }
                if (stopRequested.value) break
                // Closed by the server although we are still listening: treat as a dropped connection.
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (stopRequested.value) break // stopped while the connection was down: nothing left to finalise
                if (!e.isRetryable()) throw e
                _state.update { it.copy(phase = Phase.RECONNECTING) }
            }
            _state.update { it.withoutProvisional() }
            delay(minOf(1_000L shl attempt.coerceAtMost(4), MAX_RECONNECT_DELAY_MS))
            attempt++
            if (stopRequested.value) break
        }
        capture.cancel()
        autosave.cancel()
    }

    /** Stops the microphone, then lets Soniox finalise the remaining text before the session closes. */
    fun stop() {
        val job = sessionJob ?: return
        if (!job.isActive) return
        stopRequested.value = true
        _state.update { it.copy(phase = Phase.STOPPING, levelDb = SILENCE_DBFS) }
        scope.launch {
            delay(FINISH_TIMEOUT_MS)
            job.cancel()
        }
    }

    /** Forgets all text and starts a new transcript file; only between sessions. */
    fun clear() {
        if (sessionJob?.isActive == true) return
        _state.value = SessionState()
        transcriptFile = null
    }

    private fun saveTranscript() {
        val file = transcriptFile ?: return
        val text = _state.value.segments.toPlainText()
        scope.launch(fileWriter) { file.writeText(text + "\n") }
    }

    /** e.g. recordings/20261012-191500_UNPROCESSED_+12dB.wav in the app's external files directory. */
    private fun newFile(dir: String, suffix: String): File {
        val parent = File(context.getExternalFilesDir(null), dir).apply { mkdirs() }
        return File(parent, timestamp("yyyyMMdd-HHmmss") + suffix)
    }
}

private fun timestamp(pattern: String) = SimpleDateFormat(pattern, Locale.US).format(Date())

private fun Throwable.isRetryable() = this is IOException || (this is SonioxException && retryable)

private fun SessionState.withTokens(tokens: List<Token>): SessionState {
    val current = segments.last()
    val updated = current.copy(
        romanian = current.romanian.update(tokens.filterNot { it.isTranslation }),
        english = current.english.update(tokens.filter { it.isTranslation }),
    )
    return copy(segments = segments.dropLast(1) + updated)
}

private fun SessionState.withoutProvisional(): SessionState {
    val current = segments.last()
    val updated = current.copy(
        romanian = Transcript(current.romanian.final),
        english = Transcript(current.english.final),
    )
    return copy(segments = segments.dropLast(1) + updated)
}
