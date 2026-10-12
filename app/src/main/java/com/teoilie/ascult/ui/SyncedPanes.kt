package com.teoilie.ascult.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.teoilie.ascult.soniox.Segment
import com.teoilie.ascult.soniox.Transcript
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.roundToInt

/**
 * Romanian above English, scrolled together. While following live, both stick to the end as text
 * arrives. Scrolling either pane stops following and keeps the other aligned by segment: the same
 * fraction through the same segment sits at the centre of both. Reaching the end resumes following.
 */
@Composable
fun SyncedPanes(segments: List<Segment>, textScale: Float, modifier: Modifier = Modifier) {
    val romanian = remember { PaneScroll() }
    val english = remember { PaneScroll() }
    var followLive by remember { mutableStateOf(true) }
    var leader by remember { mutableStateOf<PaneScroll?>(null) } // the pane the user last dragged

    for (pane in listOf(romanian, english)) {
        LaunchedEffect(pane) {
            snapshotFlow { followLive to pane.scroll.maxValue }.collectLatest { (follow, end) ->
                if (follow) pane.scroll.animateScrollTo(end)
            }
        }
        LaunchedEffect(pane) {
            pane.scroll.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> {
                        leader = pane
                        if (pane.scroll.canScrollForward || pane.scroll.canScrollBackward) followLive = false
                    }
                    is DragInteraction.Stop, is DragInteraction.Cancel ->
                        if (!pane.scroll.canScrollForward) followLive = true
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { leader?.let { it to it.scroll.value } }.filterNotNull().collectLatest { (lead, _) ->
            if (followLive) return@collectLatest
            if (!lead.scroll.canScrollForward) {
                followLive = true
                return@collectLatest
            }
            val other = if (lead === romanian) english else romanian
            other.scrollToMatch(lead)
        }
    }

    Box(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Pane("Română", segments, Segment::romanian, romanian, textScale, placeholder = "Transcrierea va apărea aici.")
            HorizontalDivider()
            Pane("English", segments, Segment::english, english, textScale, placeholder = "The translation will appear here.")
        }
        if (!followLive && segments.isNotEmpty()) {
            FilledTonalButton(
                onClick = { followLive = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) { Text("Jump to live") }
        }
    }
}

/** A pane's scroll position plus where each segment starts in its text. */
private class PaneScroll {
    val scroll = ScrollState(0)
    var layout by mutableStateOf(PaneLayout())

    /** Scrolls so the point at the centre of [lead] (segment and fraction through it) is centred here too. */
    suspend fun scrollToMatch(lead: PaneScroll) {
        val (segment, fraction) = lead.layout.locate(lead.scroll.value + lead.scroll.viewportSize / 2f)
        val target = layout.position(segment, fraction) - scroll.viewportSize / 2f
        scroll.scrollTo(target.roundToInt().coerceIn(0, scroll.maxValue))
    }
}

/** Top of each segment (px) within a pane's text, and the text's height. */
private class PaneLayout(private val segmentTops: List<Float> = emptyList(), private val height: Float = 0f) {
    fun locate(y: Float): Pair<Int, Float> {
        val segment = segmentTops.indexOfLast { it <= y }.coerceAtLeast(0)
        val top = segmentTops.getOrElse(segment) { 0f }
        val end = segmentTops.getOrElse(segment + 1) { height }
        return segment to if (end > top) ((y - top) / (end - top)).coerceIn(0f, 1f) else 0f
    }

    fun position(segment: Int, fraction: Float): Float {
        val top = segmentTops.getOrNull(segment) ?: return height
        val end = segmentTops.getOrElse(segment + 1) { height }
        return top + fraction * (end - top)
    }
}

/** Each segment under its settings label; final text in full colour, provisional text dimmed. */
@Composable
private fun ColumnScope.Pane(
    label: String,
    segments: List<Segment>,
    transcriptOf: (Segment) -> Transcript,
    pane: PaneScroll,
    textScale: Float,
    placeholder: String,
) {
    val body = MaterialTheme.typography.bodyLarge
    val textStyle = body.copy(fontSize = body.fontSize * textScale, lineHeight = body.lineHeight * textScale)
    val dimmed = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val segmentLabel = SpanStyle(color = MaterialTheme.colorScheme.primary, fontSize = MaterialTheme.typography.labelSmall.fontSize)
    val segmentStarts = mutableListOf<Int>()
    val text = if (segments.isEmpty()) {
        AnnotatedString(placeholder, SpanStyle(color = dimmed))
    } else {
        buildAnnotatedString {
            segments.forEachIndexed { i, segment ->
                if (i > 0) append("\n\n")
                segmentStarts += length
                withStyle(segmentLabel) { append(segment.label) }
                append("\n")
                val transcript = transcriptOf(segment)
                val final = transcript.final.trimStart()
                append(final)
                withStyle(SpanStyle(color = dimmed)) {
                    append(if (final.isEmpty()) transcript.provisional.trimStart() else transcript.provisional)
                }
            }
        }
    }

    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            text,
            style = textStyle,
            modifier = Modifier.padding(top = 8.dp).verticalScroll(pane.scroll),
            onTextLayout = { result: TextLayoutResult ->
                pane.layout = PaneLayout(
                    segmentTops = segmentStarts.map { result.getLineTop(result.getLineForOffset(it)) },
                    height = result.size.height.toFloat(),
                )
            },
        )
    }
}
