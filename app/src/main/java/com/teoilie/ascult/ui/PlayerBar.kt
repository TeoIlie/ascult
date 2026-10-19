package com.teoilie.ascult.ui

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.teoilie.ascult.LOG_TAG
import com.teoilie.ascult.R
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.util.Locale

private const val SKIP_MS = 10_000
private val SPEEDS = listOf(1f, 1.25f, 1.5f, 0.75f)

/**
 * A saved sermon's recording: seek bar, back/forward 10 s, play/pause and speed. Playback pauses
 * when the app leaves the screen, and is off while [enabled] is false (listening).
 */
@Composable
internal fun PlayerBar(file: File, enabled: Boolean, modifier: Modifier = Modifier) {
    var duration by remember(file) { mutableIntStateOf(0) } // 0 until prepared
    var position by remember(file) { mutableIntStateOf(0) }
    var playing by remember(file) { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(0) } // index into SPEEDS
    var dragging by remember { mutableStateOf<Float?>(null) } // slider position while dragged

    // Prepared asynchronously; the listeners run on the main thread.
    val player = remember(file) {
        MediaPlayer().apply {
            setOnPreparedListener { duration = it.duration }
            setOnCompletionListener {
                playing = false
                position = duration
            }
            setOnErrorListener { _, what, extra ->
                Log.w(LOG_TAG, "Can't play ${file.path}: $what/$extra")
                playing = false
                duration = 0 // the player is unusable now: disables the controls
                true
            }
            try {
                setDataSource(file.path)
                prepareAsync()
            } catch (e: IOException) {
                Log.w(LOG_TAG, "Can't open ${file.path}", e)
            }
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(playing) {
        while (playing) {
            position = player.currentPosition
            delay(250)
        }
    }

    fun pause() {
        if (!playing) return
        player.pause()
        position = player.currentPosition
        playing = false
    }

    fun play() {
        if (position >= duration) player.seekTo(0)
        player.playbackParams = PlaybackParams().setSpeed(SPEEDS[speed]) // also starts playback
        player.start()
        playing = true
    }

    fun seek(to: Int) {
        position = to.coerceIn(0, duration)
        player.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { pause() }
    LaunchedEffect(enabled) { if (!enabled) pause() }
    val ready = enabled && duration > 0

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val shown = dragging ?: position.toFloat()
                TimeText(shown.toInt(), TextAlign.Start)
                Slider(
                    value = shown,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { seek(it.toInt()) }
                        dragging = null
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                )
                TimeText(duration, TextAlign.End)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = {
                        speed = (speed + 1) % SPEEDS.size
                        if (playing) player.playbackParams = PlaybackParams().setSpeed(SPEEDS[speed])
                    },
                    modifier = Modifier.width(SPEED_WIDTH),
                ) { Text(speedLabel(SPEEDS[speed])) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { seek(position - SKIP_MS) }, enabled = ready, modifier = Modifier.size(52.dp)) {
                    Icon(painterResource(R.drawable.ic_replay_10), contentDescription = "Back 10 seconds", Modifier.size(28.dp))
                }
                PlayPauseButton(playing, enabled = ready, onClick = { if (playing) pause() else play() })
                IconButton(onClick = { seek(position + SKIP_MS) }, enabled = ready, modifier = Modifier.size(52.dp)) {
                    Icon(painterResource(R.drawable.ic_forward_10), contentDescription = "Forward 10 seconds", Modifier.size(28.dp))
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(SPEED_WIDTH)) // balances the speed button, so play stays centred
            }
        }
    }
}

private val SPEED_WIDTH = 76.dp

/** Play/pause in the Start/Stop button's shape: round while paused, squarer while playing. */
@Composable
private fun PlayPauseButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val corner by animateDpAsState(
        if (playing) 18.dp else 28.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "corner",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.padding(horizontal = 8.dp).size(width = 80.dp, height = 56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = if (playing) "Pause" else "Play",
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun TimeText(ms: Int, align: TextAlign) {
    Text(
        clockTime(ms),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = Modifier.width(48.dp),
    )
}

/** e.g. "4:05", "1:02:09" */
internal fun clockTime(ms: Int): String {
    val seconds = ms / 1000
    val (h, m, s) = Triple(seconds / 3600, seconds / 60 % 60, seconds % 60)
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/** e.g. "1×", "1.25×" */
internal fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"
