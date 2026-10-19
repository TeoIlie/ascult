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
import com.teoilie.ascult.history.HistoryEntry
import com.teoilie.ascult.history.HistoryStore
import com.teoilie.ascult.settings.AudioSettings
import com.teoilie.ascult.settings.SettingsStore
import com.teoilie.ascult.settings.Translator
import com.teoilie.ascult.soniox.ClaudeText
import com.teoilie.ascult.soniox.Dictionary
import com.teoilie.ascult.soniox.Segment
import com.teoilie.ascult.soniox.SonioxClient
import com.teoilie.ascult.soniox.SonioxException
import com.teoilie.ascult.soniox.text
import com.teoilie.ascult.soniox.withClaudeEnglish
import com.teoilie.ascult.soniox.withClaudeFailed
import com.teoilie.ascult.soniox.withTokens
import com.teoilie.ascult.soniox.withoutProvisional
import com.teoilie.ascult.translation.ClaudeClient
import com.teoilie.ascult.translation.ClaudeException
import com.teoilie.ascult.translation.ClaudeTranslator
import com.teoilie.ascult.translation.nextChunk
import com.teoilie.ascult.translation.systemPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How long to wait for Soniox to finalise the last tokens after Stop before giving up. */
private const val FINISH_TIMEOUT_MS = 5_000L

/** In Claude mode, how long after Soniox has finished to wait for the last sentences' translation. */
private const val TRANSLATION_FLUSH_TIMEOUT_MS = 10_000L

/** Audio kept while reconnecting (sent once the connection is back); older frames are dropped. */
private const val RECONNECT_BUFFER_FRAMES = 300 // 30 s

private const val MAX_RECONNECT_DELAY_MS = 10_000L

/** How often the run's draft is saved while listening, so little is lost if the process is killed. */
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
    /** The current run's text: one segment, or none before the first Start and after Save or Delete. */
    val segments: List<Segment> = emptyList(),
    val error: String? = null,
    /** A problem that doesn't stop listening, such as Claude translation retrying or giving up. */
    val notice: String? = null,
    /** WAV file of the current run's audio, if recording was on. */
    val recording: File? = null,
    /** The current run's History draft. */
    val entry: HistoryEntry? = null,
    /** The run has stopped and waits for Save or Delete; Start is not possible until then. */
    val pending: Boolean = false,
) {
    val listening: Boolean get() = phase != Phase.IDLE
}

/** One-off outcomes for the screen to announce. */
sealed interface SessionEvent {
    data class Saved(val entryId: String) : SessionEvent
    data object Deleted : SessionEvent
}

/**
 * Microphone → Soniox → transcripts. Lives for the whole process so it survives the screen going
 * away; TranslationService keeps the process and microphone alive while [SessionState.listening].
 */
class TranslationSession(
    private val context: Context,
    private val settings: SettingsStore,
    private val history: HistoryStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val soniox = SonioxClient()
    private val claude = ClaudeClient()

    /** Kept current so gain changes apply to a running session. */
    private val audioSettings = settings.audio.stateIn(scope, SharingStarted.Eagerly, AudioSettings())
    private val stopRequested = MutableStateFlow(false)
    private var sessionJob: Job? = null

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    /** What the user chose for the current run while it was still finishing; applied when it ends. */
    private var decision: Decision? = null

    /** When the microphone stopped; the run's end time. */
    private var stoppedAt: Long? = null

    /** The run last deleted from the screen, for Undo. */
    private var deleted: SessionState? = null

    /** Starts listening; returns false (with [SessionState.error] set) if it cannot. */
    suspend fun start(): Boolean {
        if (sessionJob?.isActive == true) return true
        if (_state.value.pending) return false
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

        // Source, recording and translator are fixed for the session; gain is read per frame so it can be adjusted live.
        val audioSettings = settings.audio.first()
        val claudeKey = if (audioSettings.translator == Translator.CLAUDE) {
            settings.anthropicApiKey.first().ifBlank { BuildConfig.ANTHROPIC_API_KEY }.ifBlank {
                _state.update { it.copy(error = "No Anthropic API key: add one in Settings") }
                return false
            }
        } else {
            null
        }
        val startedAt = System.currentTimeMillis()
        val entry = history.create(audioSettings, startedAt)
        history.write(entry)
        val recording = history.recordingFile(entry)
        val label = "${audioSettings.summary} · ${SimpleDateFormat("HH:mm", Locale.US).format(Date(startedAt))}"

        stopRequested.value = false
        decision = null
        stoppedAt = null
        deleted = null
        val segment = Segment(label, claude = claudeKey?.let { ClaudeText() })
        _state.value = SessionState(Phase.LISTENING, recording = recording, segments = listOf(segment), entry = entry)
        sessionJob = scope.launch {
            try {
                listen(apiKey, claudeKey, audioSettings, recording)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(phase = Phase.IDLE, levelDb = SILENCE_DBFS) }
                if (decision != Decision.Delete) finishRun()
            }
        }
        return true
    }

    /** The current run's draft with its text so far. */
    private fun draft(): HistoryEntry? {
        val state = _state.value
        val segment = state.segments.lastOrNull() ?: return null
        return state.entry?.withText(segment, stoppedAt ?: System.currentTimeMillis())
    }

    /** Once the run has ended: saves it if Save was chosen, drops it if it has no text, else asks. */
    private fun finishRun() {
        val entry = draft() ?: return
        val save = decision as? Decision.Save
        when {
            save != null -> {
                history.write(entry.copy(title = save.title, saved = true))
                _state.value = SessionState()
                _events.tryEmit(SessionEvent.Saved(entry.id))
            }
            entry.romanian.isEmpty() -> {
                history.discard(entry.id)
                _state.value = SessionState(error = _state.value.error)
            }
            else -> {
                history.write(entry)
                _state.update { it.copy(pending = true, entry = entry) }
            }
        }
    }

    /**
     * Captures into a buffer that outlives individual connections, and (re)connects to Soniox until
     * the audio ends. A dropped connection loses only that connection's provisional text. With a
     * [claudeKey], Claude translates the finished Romanian alongside, and finishes after Soniox.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun listen(apiKey: String, claudeKey: String?, audioSettings: AudioSettings, recording: File?) = coroutineScope {
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
                draft()?.let(history::write)
            }
        }

        val sonioxFinished = MutableStateFlow(false)
        val translation = claudeKey?.let { key ->
            val instructions = withContext(Dispatchers.IO) { ClaudeTranslator.instructions(context) }
            val translator = ClaudeTranslator(claude, key, systemPrompt(instructions, dictionary))
            launch { translate(translator, sonioxFinished) }
        }

        var attempt = 0
        while (true) {
            try {
                soniox.transcribe(apiKey, dictionary, audio.receiveAsFlow()).collect { tokens ->
                    attempt = 0
                    _state.update {
                        val phase = if (it.phase == Phase.RECONNECTING) Phase.LISTENING else it.phase
                        it.copy(phase = phase, error = null, segments = it.segments.withTokens(tokens))
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
            _state.update { it.copy(segments = it.segments.withoutProvisional()) }
            delay(backoff(attempt++))
            if (stopRequested.value) break
        }
        capture.cancel()
        autosave.cancel()
        if (translation != null) {
            sonioxFinished.value = true // the Romanian is complete: translate the rest, including fragments
            withTimeoutOrNull(TRANSLATION_FLUSH_TIMEOUT_MS) { translation.join() }
            translation.cancel()
        }
    }

    /**
     * Sends the current segment's finished Romanian to Claude a sentence at a time (see [nextChunk]),
     * one request at a time: sentences that finish during a request go together in the next one.
     * Retries with backoff while the failure is temporary; otherwise gives up for this segment and
     * leaves Soniox's English showing. Returns once [sonioxFinished] and everything is translated.
     */
    private suspend fun translate(translator: ClaudeTranslator, sonioxFinished: StateFlow<Boolean>) {
        val romanian = _state.map { it.segments.last().romanian.final }
        var attempt = 0
        while (true) {
            val finished = sonioxFinished.value
            val segment = _state.value.segments.last()
            val claude = segment.claude ?: return
            val chunk = nextChunk(segment.romanian.final, claude.translatedUpTo, flush = finished)
            if (chunk == null) {
                if (finished) return
                combine(romanian, sonioxFinished) { text, done -> text != segment.romanian.final || done }.first { it }
                continue
            }
            try {
                val english = translator.translate(chunk.text)
                attempt = 0
                _state.update { it.copy(notice = null, segments = it.segments.withClaudeEnglish(english, chunk.end)) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!e.isRetryable()) {
                    _state.update {
                        it.copy(notice = "Claude translation failed: ${e.message}", segments = it.segments.withClaudeFailed())
                    }
                    return
                }
                _state.update { it.copy(notice = "Claude: retrying") }
                delay(backoff(attempt++))
            }
        }
    }

    /** Stops the microphone, then lets Soniox finalise the remaining text before the session closes. */
    fun stop() {
        val job = sessionJob ?: return
        if (!job.isActive) return
        stopRequested.value = true
        val now = System.currentTimeMillis()
        stoppedAt = now
        // Ask straight away if there is text; Save waits for the run to finish.
        _state.update { state ->
            val hasText = state.segments.any { it.romanian.text().isNotEmpty() }
            state.copy(phase = Phase.STOPPING, levelDb = SILENCE_DBFS, pending = hasText, entry = state.entry?.copy(endedAt = now))
        }
        val translating = _state.value.segments.lastOrNull()?.claude != null
        scope.launch {
            delay(FINISH_TIMEOUT_MS + if (translating) TRANSLATION_FLUSH_TIMEOUT_MS else 0L)
            job.cancel()
        }
    }

    /** Keeps the run in History as [title]; if it is still finishing, once it has finished. */
    fun save(title: String) {
        val state = _state.value
        if (!state.pending) return
        decision = Decision.Save(title.trim().ifEmpty { state.entry?.title.orEmpty() })
        _state.update { it.copy(pending = false) }
        if (sessionJob?.isActive != true) finishRun()
    }

    /** Throws the run away (into the trash until [undoDelete] is no longer offered) and clears the screen. */
    fun delete() {
        val state = _state.value
        val entry = draft() ?: return
        if (!state.pending) return
        decision = Decision.Delete
        sessionJob?.cancel()
        history.write(entry)
        scope.launch { history.delete(listOf(entry.id)) }
        deleted = state.copy(phase = Phase.IDLE, levelDb = SILENCE_DBFS, entry = entry)
        _state.value = SessionState()
        _events.tryEmit(SessionEvent.Deleted)
    }

    /** Brings back the run [delete] threw away, waiting for Save or Delete again. */
    fun undoDelete() {
        val restored = deleted ?: return
        if (sessionJob?.isActive == true || _state.value.pending) return
        deleted = null
        decision = null
        scope.launch {
            history.restore(listOfNotNull(restored.entry?.id))
            _state.value = restored
        }
    }

    /** The Undo for [delete] has gone: remove the run for good. */
    fun forgetDeleted() {
        if (deleted == null) return
        deleted = null
        history.emptyTrash()
    }

    private sealed interface Decision {
        data class Save(val title: String) : Decision
        data object Delete : Decision
    }
}

private fun Throwable.isRetryable() =
    this is IOException || (this is SonioxException && retryable) || (this is ClaudeException && retryable)

/** Delay before retry number [attempt] (from 0): 1, 2, 4, 8, then 10 s. */
private fun backoff(attempt: Int): Long = minOf(1_000L shl attempt.coerceAtMost(4), MAX_RECONNECT_DELAY_MS)
