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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.teoilie.ascult.settings.EVEN_SPLIT
import com.teoilie.ascult.settings.TEXT_SCALES
import com.teoilie.ascult.soniox.Segment
import com.teoilie.ascult.soniox.Transcript
import com.teoilie.ascult.soniox.toPlainText
import kotlin.math.roundToInt

/** What the bottom bar's controls do. Built once per screen, so the bar doesn't recompose for new lambdas. */
private class BarActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val clear: () -> Unit,
    val share: () -> Unit,
    val changeTextScale: (steps: Int) -> Unit,
    val openAudioSettings: () -> Unit,
    val openSettings: () -> Unit,
)

@Composable
fun TranslationScreen(onOpenSettings: () -> Unit, viewModel: TranslationViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val audioSettings by viewModel.audioSettings.collectAsStateWithLifecycle()
    val textScale by viewModel.textScale.collectAsStateWithLifecycle()
    val paneSplit by viewModel.paneSplit.collectAsStateWithLifecycle()
    val claudeKeySet by viewModel.claudeKeySet.collectAsStateWithLifecycle()
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

    val actions = remember(viewModel, context, permissionLauncher, onOpenSettings) {
        BarActions(
            start = {
                // The microphone is required; notifications only make the session's Stop button visible.
                val missing = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS).filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) viewModel.start() else permissionLauncher.launch(missing.toTypedArray())
            },
            stop = viewModel::stop,
            clear = viewModel::clear,
            share = { share(context, "Ascult transcript", viewModel.state.value.segments.toPlainText()) },
            changeTextScale = viewModel::changeTextScale,
            openAudioSettings = { showAudioSettings = true },
            openSettings = onOpenSettings,
        )
    }

    TranslationContent(state, audioSettings, textScale, paneSplit, viewModel::setPaneSplit, actions)

    if (showAudioSettings) {
        AudioSettingsSheet(
            settings = audioSettings,
            listening = state.listening,
            claudeKeySet = claudeKeySet,
            onChange = viewModel::updateAudio,
            onDismiss = { showAudioSettings = false },
        )
    }
}

/** Romanian and English panes with a draggable divider; status and controls in a bar docked at the bottom. */
@Composable
private fun TranslationContent(
    state: SessionState,
    audioSettings: AudioSettings,
    textScale: Float,
    paneSplit: Float,
    onPaneSplit: (Float) -> Unit,
    actions: BarActions,
) {
    Scaffold(
        bottomBar = { BottomBar(state, audioSettings, textScale, actions) },
    ) { padding ->
        SyncedPanes(
            segments = state.segments,
            textScale = textScale,
            split = paneSplit,
            onSplitChange = onPaneSplit,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        )
    }
}

/** Status on the left (opens the audio settings); text size, ⋮ menu and Start/Stop on the right, in thumb reach. */
@Composable
private fun BottomBar(state: SessionState, audioSettings: AudioSettings, textScale: Float, actions: BarActions) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBlock(state, audioSettings, actions.openAudioSettings, Modifier.weight(1f))
            TextSizeMenu(textScale, actions.changeTextScale)
            OverflowMenu(
                canShare = state.segments.isNotEmpty(),
                canClear = !state.listening && state.segments.isNotEmpty(),
                actions = actions,
            )
            StartStopButton(state.phase, onStart = actions.start, onStop = actions.stop, Modifier.padding(start = 6.dp))
        }
    }
}

/** The bar's two lines of status: what is happening, and the audio settings in use (or the error). */
internal data class StatusText(val title: String, val detail: String, val isError: Boolean = false)

internal fun statusText(state: SessionState, audio: AudioSettings): StatusText = when {
    state.error != null -> StatusText("Error", state.error, isError = true)
    state.phase == Phase.RECONNECTING -> StatusText("Reconnecting…", "Audio is kept and sent once back")
    state.phase == Phase.STOPPING -> StatusText("Finishing…", audio.summary)
    state.phase == Phase.LISTENING && state.recording != null -> StatusText("Recording", audio.summary)
    state.phase == Phase.LISTENING -> StatusText("Listening", audio.summary)
    audio.saveRecording -> StatusText("Ready", "${audio.summary} · Recording on")
    else -> StatusText("Ready", audio.summary)
}

/** Level meter and [statusText]; tapping it opens the audio settings. */
@Composable
private fun StatusBlock(state: SessionState, audioSettings: AudioSettings, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = statusText(state, audioSettings)
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Audio settings", onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MiniLevelMeter(state.levelDb, listening = state.phase == Phase.LISTENING)
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.phase == Phase.LISTENING) {
                    Box(Modifier.size(8.dp).background(colors.error, CircleShape))
                }
                Text(
                    status.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (status.isError) colors.error else colors.onSurface,
                )
            }
            Text(
                status.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.isError) colors.error else colors.onSurfaceVariant,
                maxLines = if (status.isError) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Share, Clear, Audio settings and Settings. */
@Composable
private fun OverflowMenu(canShare: Boolean, canClear: Boolean, actions: BarActions) {
    IconMenu(R.drawable.ic_more_vert, "More") { close ->
        @Composable
        fun Item(label: String, @DrawableRes icon: Int, enabled: Boolean = true, onClick: () -> Unit) = DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
            enabled = enabled,
            onClick = {
                close()
                onClick()
            },
        )
        Item("Share transcript", R.drawable.ic_share, enabled = canShare, onClick = actions.share)
        Item("Clear transcript", R.drawable.ic_delete, enabled = canClear, onClick = actions.clear)
        Item("Audio settings", R.drawable.ic_tune, onClick = actions.openAudioSettings)
        Item("Settings", R.drawable.ic_settings, onClick = actions.openSettings)
    }
}

/** A small − size + stepper over [TEXT_SCALES]. */
@Composable
private fun TextSizeMenu(textScale: Float, onTextScale: (steps: Int) -> Unit) {
    IconMenu(R.drawable.ic_text_size, "Text size") {
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

/** Start/Stop pill: a mic while idle, springing to a squarer Stop while listening. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class) // LoadingIndicator
@Composable
private fun StartStopButton(phase: Phase, onStart: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val idle = phase == Phase.IDLE
    val corner by animateDpAsState(
        if (idle) 28.dp else 18.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "corner",
    )
    Surface(
        onClick = if (idle) onStart else onStop,
        enabled = phase != Phase.STOPPING,
        shape = RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.size(width = 80.dp, height = 56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (phase == Phase.STOPPING) {
                LoadingIndicator(Modifier.size(36.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(
                    painterResource(if (idle) R.drawable.ic_mic else R.drawable.ic_stop),
                    contentDescription = if (idle) "Start" else "Stop",
                    modifier = Modifier.size(26.dp),
                )
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
    val actions = BarActions(start = {}, stop = {}, clear = {}, share = {}, changeTextScale = {}, openAudioSettings = {}, openSettings = {})
    AscultTheme(dark = true) {
        TranslationContent(state, AudioSettings(), textScale = 1f, paneSplit = EVEN_SPLIT, onPaneSplit = {}, actions = actions)
    }
}
