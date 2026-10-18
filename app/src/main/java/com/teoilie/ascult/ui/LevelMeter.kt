package com.teoilie.ascult.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.teoilie.ascult.audio.SILENCE_DBFS

/** Relative bar heights, so one level reads as a small waveform. */
private val BAR_SHAPE = listOf(0.5f, 0.8f, 1f, 0.7f, 0.9f)

/** Level as a fraction of the meter: [SILENCE_DBFS] and below is 0, 0 dBFS is 1. */
internal fun levelFraction(levelDb: Float): Float = ((levelDb - SILENCE_DBFS) / -SILENCE_DBFS).coerceIn(0f, 1f)

/** Five small bars that move with the input level; flat while not listening. */
@Composable
fun MiniLevelMeter(levelDb: Float, listening: Boolean, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(if (listening) levelFraction(levelDb) else 0f, label = "level")
    val color = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.size(width = 23.dp, height = 16.dp)) {
        val bar = 3.dp.toPx()
        val step = bar + 2.dp.toPx()
        BAR_SHAPE.forEachIndexed { i, shape ->
            val height = bar + (size.height - bar) * (fraction * shape).coerceAtMost(1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * step, (size.height - height) / 2),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}
