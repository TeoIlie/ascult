package com.teoilie.ascult.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.teoilie.ascult.audio.SILENCE_DBFS

private const val BARS = 44
private const val RECENT_BARS = 10

/** Level as a fraction of the meter: [SILENCE_DBFS] and below is 0, 0 dBFS is 1. */
internal fun levelFraction(levelDb: Float): Float = ((levelDb - SILENCE_DBFS) / -SILENCE_DBFS).coerceIn(0f, 1f)

/**
 * Recent input levels as a row of bars, newest on the right and brightest; one bar per level
 * update (every 100 ms frame). Flat while not listening.
 */
@Composable
fun LevelWaveform(levelDb: Float, listening: Boolean, modifier: Modifier = Modifier) {
    val levels = remember { mutableStateListOf<Float>().apply { repeat(BARS) { add(0f) } } }
    LaunchedEffect(levelDb, listening) {
        if (listening) {
            levels.removeAt(0)
            levels.add(levelFraction(levelDb))
        } else {
            for (i in levels.indices) levels[i] = 0f
        }
    }

    val recent = MaterialTheme.colorScheme.primary
    val older = recent.copy(alpha = 0.6f)
    val idle = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.height(40.dp)) {
        val bar = 3.dp.toPx()
        val step = bar + 3.dp.toPx()
        val minHeight = bar
        val left = (size.width - (BARS * step - (step - bar))) / 2
        levels.forEachIndexed { i, level ->
            val height = (level * size.height).coerceAtLeast(minHeight)
            drawRoundRect(
                color = if (!listening) idle else if (i >= BARS - RECENT_BARS) recent else older,
                topLeft = Offset(left + i * step, (size.height - height) / 2),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}
