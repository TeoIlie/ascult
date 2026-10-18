package com.teoilie.ascult.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.teoilie.ascult.R
import com.teoilie.ascult.soniox.Segment
import com.teoilie.ascult.soniox.Transcript
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.roundToInt

private val LABEL_HEIGHT = 40.dp
private val HANDLE_HEIGHT = 32.dp

/** Smallest share a pane keeps while the handle is being dragged. */
private const val DRAG_MIN = 0.02f

/**
 * Romanian above English, scrolled together. While following live, both stick to the end as text
 * arrives. Scrolling either pane stops following and keeps the other aligned by segment: the same
 * fraction through the same segment sits at the centre of both. Reaching the end resumes following.
 *
 * The handle between them sets [split], the Romanian share of the text height; dragging it near
 * either end collapses that pane to its label. [onSplitChange] gets the settled value to save.
 */
@Composable
fun SyncedPanes(
    segments: List<Segment>,
    textScale: Float,
    split: Float,
    onSplitChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
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

    // Follows the handle while dragging; reset whenever the saved value changes.
    var liveSplit by remember(split) { mutableFloatStateOf(split) }
    var textHeight by remember { mutableIntStateOf(0) }
    val fixedHeight = with(LocalDensity.current) { (LABEL_HEIGHT * 2 + HANDLE_HEIGHT).toPx() }
    val settle = { value: Float -> liveSplit = value; onSplitChange(value) }

    Box(modifier) {
        Column(Modifier.fillMaxSize().onSizeChanged { textHeight = (it.height - fixedHeight).roundToInt() }) {
            Pane(
                label = "Română",
                color = MaterialTheme.colorScheme.primary,
                share = liveSplit,
                expandIcon = R.drawable.ic_expand_more,
                onExpand = { settle(0.5f) },
                segments = segments,
                transcriptOf = Segment::romanian,
                pane = romanian,
                textScale = textScale,
                placeholder = "Tap the microphone to start. The Romanian transcript appears here.",
            )
            DividerHandle(
                // Panes only collapse on release (snapSplit), so the layout never jumps under the finger.
                onDrag = { delta ->
                    if (textHeight > 0) liveSplit = (liveSplit + delta / textHeight).coerceIn(DRAG_MIN, 1f - DRAG_MIN)
                },
                onDragStopped = { settle(snapSplit(liveSplit)) },
                onSet = settle,
            )
            Pane(
                label = "English",
                color = MaterialTheme.colorScheme.tertiary,
                share = 1f - liveSplit,
                expandIcon = R.drawable.ic_expand_less,
                onExpand = { settle(0.5f) },
                segments = segments,
                transcriptOf = Segment::english,
                pane = english,
                textScale = textScale,
                placeholder = "The English translation appears here.",
            )
        }
        if (!followLive && segments.isNotEmpty()) {
            FilledTonalButton(
                onClick = { followLive = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Jump to live")
            }
        }
    }
}

/** Snaps a split dragged most of the way to either end shut, collapsing the smaller pane. */
internal fun snapSplit(split: Float): Float = when {
    split < 0.15f -> 0f
    split > 0.85f -> 1f
    else -> split.coerceIn(0f, 1f)
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
internal class PaneLayout(private val segmentTops: List<Float> = emptyList(), private val height: Float = 0f) {
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

/** A pane's text and the offset where each segment starts. */
internal class PaneText(val text: AnnotatedString, val segmentStarts: List<Int>)

/** Segments as paragraphs, text only; final text plain, provisional text in [provisionalStyle]. */
internal fun paneText(segments: List<Segment>, transcriptOf: (Segment) -> Transcript, provisionalStyle: SpanStyle): PaneText {
    val segmentStarts = mutableListOf<Int>()
    val text = buildAnnotatedString {
        segments.forEachIndexed { i, segment ->
            if (i > 0) append("\n\n")
            segmentStarts += length
            val transcript = transcriptOf(segment)
            val final = transcript.final.trimStart()
            append(final)
            withStyle(provisionalStyle) {
                append(if (final.isEmpty()) transcript.provisional.trimStart() else transcript.provisional)
            }
        }
    }
    return PaneText(text, segmentStarts)
}

/** Label row, then the text taking [share] of the panes' text height; at a share of 0 only the label shows. */
@Composable
private fun ColumnScope.Pane(
    label: String,
    color: Color,
    share: Float,
    expandIcon: Int,
    onExpand: () -> Unit,
    segments: List<Segment>,
    transcriptOf: (Segment) -> Transcript,
    pane: PaneScroll,
    textScale: Float,
    placeholder: String,
) {
    val collapsed = share <= 0f
    Row(Modifier.fillMaxWidth().height(LABEL_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = color)
        if (collapsed) {
            IconButton(onClick = onExpand) {
                Icon(painterResource(expandIcon), contentDescription = "Show $label", tint = color)
            }
        }
    }
    if (collapsed) return

    Box(Modifier.weight(share).fillMaxWidth()) {
        if (segments.isEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
            )
            return@Box
        }
        val body = MaterialTheme.typography.bodyLarge
        val textStyle = body.copy(fontSize = body.fontSize * textScale, lineHeight = body.lineHeight * textScale)
        val provisional = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
        val content = paneText(segments, transcriptOf, provisional)
        Text(
            content.text,
            style = textStyle,
            modifier = Modifier.fillMaxWidth().verticalScroll(pane.scroll),
            onTextLayout = { result: TextLayoutResult ->
                pane.layout = PaneLayout(
                    segmentTops = content.segmentStarts.map { result.getLineTop(result.getLineForOffset(it)) },
                    height = result.size.height.toFloat(),
                )
            },
        )
    }
}

/**
 * The bar between the panes: drag to resize, double-tap for an even split. Accessibility actions
 * offer the same without dragging.
 */
@Composable
private fun DividerHandle(onDrag: (Float) -> Unit, onDragStopped: () -> Unit, onSet: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    val pill by animateColorAsState(
        if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        label = "handle",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HANDLE_HEIGHT)
            .draggable(
                state = rememberDraggableState(onDrag),
                orientation = Orientation.Vertical,
                onDragStarted = { dragging = true },
                onDragStopped = {
                    dragging = false
                    onDragStopped()
                },
            )
            .pointerInput(onSet) { detectTapGestures(onDoubleTap = { onSet(0.5f) }) }
            .semantics {
                contentDescription = "Divider between Romanian and English"
                customActions = listOf(
                    CustomAccessibilityAction("Even split") { onSet(0.5f); true },
                    CustomAccessibilityAction("Hide Romanian") { onSet(0f); true },
                    CustomAccessibilityAction("Hide English") { onSet(1f); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.size(width = 40.dp, height = 6.dp).background(pill, CircleShape))
    }
}
