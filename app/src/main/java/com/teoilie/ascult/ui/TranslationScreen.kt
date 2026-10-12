package com.teoilie.ascult.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teoilie.ascult.audio.SILENCE_DBFS
import kotlin.math.roundToInt

@Composable
fun TranslationScreen(viewModel: TranslationViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.start() // reports the error itself if permission was denied
    }

    TranslationContent(
        state = state,
        onStart = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onStop = viewModel::stop,
    )
}

/** Two panes: Romanian transcript on top, English translation below; controls at the bottom. */
@Composable
private fun TranslationContent(state: UiState, onStart: () -> Unit, onStop: () -> Unit) {
    Scaffold(
        bottomBar = { ControlBar(state, onStart, onStop) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Pane(label = "Română", text = "Transcrierea va apărea aici.")
            HorizontalDivider()
            Pane(label = "English", text = "The translation will appear here.")
        }
    }
}

@Composable
private fun ColumnScope.Pane(label: String, text: String) {
    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ControlBar(state: UiState, onStart: () -> Unit, onStop: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LevelMeter(state.levelDb, modifier = Modifier.weight(1f))
                if (state.listening) Button(onClick = onStop) { Text("Stop") }
                else Button(onClick = onStart) { Text("Start") }
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
    MaterialTheme { TranslationContent(UiState(listening = true, levelDb = -24f), onStart = {}, onStop = {}) }
}
