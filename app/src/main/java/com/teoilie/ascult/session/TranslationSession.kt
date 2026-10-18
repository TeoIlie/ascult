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
import com.teoilie.ascult.settings.Translator
import com.teoilie.ascult.soniox.ClaudeText
import com.teoilie.ascult.soniox.Dictionary
import com.teoilie.ascult.soniox.Segment
import com.teoilie.ascult.soniox.SonioxClient
import com.teoilie.ascult.soniox.SonioxException
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

/** How long to wait for Soniox to finalise the last tokens after Stop before giving up. */
private const val FINISH_TIMEOUT_MS = 5_000L

/** In Claude mode, how long after Soniox has finished to wait for the last sentences' translation. */
private const val TRANSLATION_FLUSH_TIMEOUT_MS = 10_000L

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
    /** A problem that doesn't stop listening, such as Claude translation retrying or giving up. */
    val notice: String? = null,
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
    private val claude = ClaudeClient()

    /** Claude's conversation so far; kept across Start/Stop so a sermon keeps its context, until Clear. */
    private var translator: ClaudeTranslator? = null
    private val files = SessionFiles(context, scope)

    /** Kept current so gain changes apply to a running session. */
    private val audioSettings = settings.audio.stateIn(scope, SharingStarted.Eagerly, AudioSettings())
    private val stopRequested = MutableStateFlow(false)
    private var sessionJob: Job? = null

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
        val recording = if (audioSettings.saveRecording) files.newRecording(audioSettings) else null
        files.startTranscriptIfNeeded()
        val label = "${audioSettings.summary} · ${timestamp("HH:mm")}"

        stopRequested.value = false
        val segment = Segment(label, claude = claudeKey?.let { ClaudeText() })
        _state.update { SessionState(Phase.LISTENING, recording = recording, segments = it.segments + segment) }
        sessionJob = scope.launch {
            try {
                listen(apiKey, claudeKey, audioSettings, recording)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(phase = Phase.IDLE, levelDb = SILENCE_DBFS) }
                files.saveTranscript(_state.value.segments)
            }
        }
        return true
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
                files.saveTranscript(_state.value.segments)
            }
        }

        val sonioxFinished = MutableStateFlow(false)
        val translation = claudeKey?.let { key ->
            val translator = translatorFor(key, dictionary)
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

    /** The current conversation if it used the same key and dictionary, else a new one. */
    private suspend fun translatorFor(apiKey: String, dictionary: Dictionary): ClaudeTranslator =
        translator?.takeIf { it.apiKey == apiKey && it.dictionary == dictionary }
            ?: ClaudeTranslator(
                claude,
                apiKey,
                dictionary,
                systemPrompt(withContext(Dispatchers.IO) { ClaudeTranslator.instructions(context) }, dictionary),
            ).also { translator = it }

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
        _state.update { it.copy(phase = Phase.STOPPING, levelDb = SILENCE_DBFS) }
        val translating = _state.value.segments.lastOrNull()?.claude != null
        scope.launch {
            delay(FINISH_TIMEOUT_MS + if (translating) TRANSLATION_FLUSH_TIMEOUT_MS else 0L)
            job.cancel()
        }
    }

    /** Forgets all text and starts a new transcript file; only between sessions. */
    fun clear() {
        if (sessionJob?.isActive == true) return
        _state.value = SessionState()
        translator = null
        files.resetTranscript()
    }
}

private fun Throwable.isRetryable() =
    this is IOException || (this is SonioxException && retryable) || (this is ClaudeException && retryable)

/** Delay before retry number [attempt] (from 0): 1, 2, 4, 8, then 10 s. */
private fun backoff(attempt: Int): Long = minOf(1_000L shl attempt.coerceAtMost(4), MAX_RECONNECT_DELAY_MS)
