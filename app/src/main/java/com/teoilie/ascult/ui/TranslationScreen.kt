package com.teoilie.ascult.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teoilie.ascult.R
import com.teoilie.ascult.session.Phase
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
    val paneSplit by viewModel.paneSplit.collectAsStateWithLifecycle()
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
        paneSplit = paneSplit,
        onPaneSplit = viewModel::setPaneSplit,
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

/** Top bar with status and controls; Romanian and English panes with a draggable divider fill the rest. */
@Composable
private fun TranslationContent(
    state: SessionState,
    audioSettings: AudioSettings,
    textScale: Float,
    paneSplit: Float,
    onPaneSplit: (Float) -> Unit,
    onTextScale: (steps: Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit,
    onAudioSettings: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopBar(
                state, audioSettings, textScale, onTextScale,
                onStart, onStop, onClear, onShare, onAudioSettings, onOpenSettings,
            )
        },
    ) { padding ->
        SyncedPanes(
            segments = state.segments,
            textScale = textScale,
            split = paneSplit,
            onSplitChange = onPaneSplit,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
        )
    }
}

/** Title with the status line under it; text size, ⋮ menu and Start/Stop on the right. */
@Composable
private fun TopBar(
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
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Ascult", style = MaterialTheme.typography.titleLarge)
            StatusLine(state, audioSettings, onAudioSettings)
        }
        TextSizeButton(textScale, onTextScale)
        OverflowMenu(
            canShare = state.segments.isNotEmpty(),
            canClear = !state.listening && state.segments.isNotEmpty(),
            onShare = onShare,
            onClear = onClear,
            onAudioSettings = onAudioSettings,
            onOpenSettings = onOpenSettings,
        )
        StartStopButton(state.phase, onStart, onStop, Modifier.padding(start = 4.dp))
    }
}

/**
 * Level meter and one line of status: an error, reconnecting or finishing, else the audio
 * settings in use. Tapping it opens the audio settings.
 */
@Composable
private fun StatusLine(state: SessionState, audioSettings: AudioSettings, onClick: () -> Unit) {
    val gain = "+${audioSettings.gainDb} dB"
    val text = when {
        state.error != null -> state.error
        state.phase == Phase.RECONNECTING -> "Reconnecting…"
        state.phase == Phase.STOPPING -> "Finishing…"
        state.phase == Phase.LISTENING && state.recording != null -> "Recording · $gain"
        state.phase == Phase.LISTENING -> "Listening · $gain"
        else -> "${audioSettings.source.label} · $gain"
    }
    val color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Audio settings", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.phase == Phase.LISTENING) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
        }
        MiniLevelMeter(state.levelDb, listening = state.phase == Phase.LISTENING)
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Share, Clear, Audio settings and Settings. */
@Composable
private fun OverflowMenu(
    canShare: Boolean,
    canClear: Boolean,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onAudioSettings: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    @Composable
    fun Item(label: String, @DrawableRes icon: Int, enabled: Boolean = true, onClick: () -> Unit) = DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
        enabled = enabled,
        onClick = {
            open = false
            onClick()
        },
    )
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Item("Share transcript", R.drawable.ic_share, enabled = canShare, onClick = onShare)
            Item("Clear transcript", R.drawable.ic_delete, enabled = canClear, onClick = onClear)
            Item("Audio settings", R.drawable.ic_tune, onClick = onAudioSettings)
            Item("Settings", R.drawable.ic_settings, onClick = onOpenSettings)
        }
    }
}

/** Opens a small − size + stepper over [TEXT_SCALES]. */
@Composable
private fun TextSizeButton(textScale: Float, onTextScale: (steps: Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_text_size), contentDescription = "Text size")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onTextScale(-1) }, enabled = textScale > TEXT_SCALES.first()) {
                    Icon(painterResource(R.drawable.ic_remove), contentDescription = "Smaller text")
                }
                Text(
                    "${(textScale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(64.dp),
                )
                IconButton(onClick = { onTextScale(1) }, enabled = textScale < TEXT_SCALES.last()) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = "Larger text")
                }
            }
        }
    }
}

/** Start/Stop pill: a mic while idle, springing to a squarer Stop while listening. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class) // LoadingIndicator
@Composable
private fun StartStopButton(phase: Phase, onStart: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val idle = phase == Phase.IDLE
    val corner by animateDpAsState(
        if (idle) 24.dp else 14.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "corner",
    )
    Surface(
        onClick = if (idle) onStart else onStop,
        enabled = phase != Phase.STOPPING,
        shape = RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.size(width = 72.dp, height = 48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (phase) {
                Phase.IDLE -> Icon(painterResource(R.drawable.ic_mic), contentDescription = "Start", Modifier.size(24.dp))
                Phase.LISTENING, Phase.RECONNECTING ->
                    Icon(painterResource(R.drawable.ic_stop), contentDescription = "Stop", Modifier.size(24.dp))
                Phase.STOPPING -> LoadingIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TranslationContentPreview() {
    val state = SessionState(
        phase = Phase.LISTENING,
        levelDb = -24f,
        segments = listOf(
            Segment(
                label = "Voice recognition · +0 dB · 19:42",
                romanian = Transcript("Fiindcă a cădea e omenește.", " Problema este"),
                english = Transcript("Because to fall is human.", " The problem is"),
            ),
        ),
    )
    AscultTheme(dark = true) {
        TranslationContent(
            state, AudioSettings(), textScale = 1f, paneSplit = 0.5f, onPaneSplit = {}, onTextScale = {},
            onStart = {}, onStop = {}, onClear = {}, onShare = {}, onAudioSettings = {}, onOpenSettings = {},
        )
    }
}
