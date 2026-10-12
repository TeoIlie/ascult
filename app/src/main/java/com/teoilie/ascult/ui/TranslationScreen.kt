package com.teoilie.ascult.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teoilie.ascult.audio.SILENCE_DBFS
import com.teoilie.ascult.session.SessionState
import com.teoilie.ascult.settings.AudioSettings
import com.teoilie.ascult.settings.TEXT_SCALES
import com.teoilie.ascult.soniox.Segment
import com.teoilie.ascult.soniox.Transcript
import com.teoilie.ascult.soniox.toPlainText
import kotlin.math.roundToInt

@Composable
fun TranslationScreen(onOpenSettings: () -> Unit, viewModel: TranslationViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val audioSettings by viewModel.audioSettings.collectAsStateWithLifecycle()
    val textScale by viewModel.textScale.collectAsStateWithLifecycle()
    var showAudioSettings by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // Keep the screen from timing out while listening and visible; the power button still turns it off.
    val view = LocalView.current
    DisposableEffect(view, state.listening) {
        view.keepScreenOn = state.listening
        onDispose { view.keepScreenOn = false }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.start() // reports the error itself if the microphone was denied
    }

    TranslationContent(
        state = state,
        audioSettings = audioSettings,
        textScale = textScale,
        onTextScale = viewModel::changeTextScale,
        onStart = {
            // The microphone is required; notifications only make the session's Stop button visible.
            val missing = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS).filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) viewModel.start() else permissionLauncher.launch(missing.toTypedArray())
        },
        onStop = viewModel::stop,
        onClear = viewModel::clear,
        onShare = { share(context, "Ascult transcript", state.segments.toPlainText()) },
        onAudioSettings = { showAudioSettings = true },
        onOpenSettings = onOpenSettings,
    )

    if (showAudioSettings) {
        AudioSettingsSheet(
            settings = audioSettings,
            listening = state.listening,
            onChange = viewModel::updateAudio,
            onDismiss = { showAudioSettings = false },
        )
    }
}

/** Two panes: Romanian transcript on top, English translation below; controls at the bottom. */
@Composable
private fun TranslationContent(
    state: SessionState,
    audioSettings: AudioSettings,
    textScale: Float,
    onTextScale: (steps: Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit,
    onAudioSettings: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        bottomBar = { ControlBar(state, audioSettings, onStart, onStop, onClear, onAudioSettings) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onTextScale(-1) }, enabled = textScale > TEXT_SCALES.first()) { Text("A−") }
                TextButton(onClick = { onTextScale(1) }, enabled = textScale < TEXT_SCALES.last()) { Text("A+") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onShare, enabled = state.segments.isNotEmpty()) { Text("Share") }
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            }
            SyncedPanes(state.segments, textScale, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ControlBar(
    state: SessionState,
    audioSettings: AudioSettings,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onAudioSettings: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            state.recording?.let {
                Text(
                    "${if (state.listening) "Recording to" else "Saved"} ${it.name}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The current audio settings double as the button that opens them.
                TextButton(onClick = onAudioSettings) {
                    Text("${audioSettings.source.label} · +${audioSettings.gainDb} dB")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear, enabled = !state.listening && state.segments.isNotEmpty()) {
                    Text("Clear")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LevelMeter(state.levelDb, modifier = Modifier.weight(1f))
                when {
                    state.stopping -> Button(onClick = {}, enabled = false) { Text("Stopping…") }
                    state.listening -> Button(onClick = onStop) { Text("Stop") }
                    else -> Button(onClick = onStart) { Text("Start") }
                }
            }
        }
    }
}

/** Horizontal bar from [SILENCE_DBFS] to 0 dBFS, with the numeric level beside it. */
@Composable
private fun LevelMeter(levelDb: Float, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(((levelDb - SILENCE_DBFS) / -SILENCE_DBFS).coerceIn(0f, 1f), label = "level")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f), drawStopIndicator = {})
        Text(
            text = if (levelDb <= SILENCE_DBFS) "—" else "${levelDb.roundToInt()} dB",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TranslationContentPreview() {
    val state = SessionState(
        listening = true,
        levelDb = -24f,
        segments = listOf(
            Segment(
                label = "Voice recognition · +0 dB · 19:42",
                romanian = Transcript("Fiindcă a cădea e omenește.", " Problema este"),
                english = Transcript("Because to fall is human.", " The problem is"),
            ),
        ),
    )
    MaterialTheme {
        TranslationContent(
            state, AudioSettings(), textScale = 1f, onTextScale = {},
            onStart = {}, onStop = {}, onClear = {}, onShare = {}, onAudioSettings = {}, onOpenSettings = {},
        )
    }
}
