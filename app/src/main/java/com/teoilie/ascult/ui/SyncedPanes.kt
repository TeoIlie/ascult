package com.teoilie.ascult.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.teoilie.ascult.R
import com.teoilie.ascult.soniox.Segment
import com.teoilie.ascult.soniox.Transcript
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.roundToInt

private val DIVIDER_HEIGHT = 40.dp
private val FADE_LENGTH = 32.dp

/** Smallest share a pane keeps while the handle is being dragged. */
private const val DRAG_MIN = 0.02f

/**
 * Romanian above English, scrolled together. While following live, both stick to the end as text
 * arrives. Scrolling either pane stops following and keeps the other aligned by segment: the same
 * fraction through the same segment sits at the centre of both. Reaching the end resumes following.
 *
 * The divider between them carries both labels and a handle that sets [split], the Romanian share
 * of the text height. Released near either end, the handle hides that pane; [onSplitChange] gets the
 * settled value to save.
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
    val dividerHeight = with(LocalDensity.current) { DIVIDER_HEIGHT.toPx() }
    val settle = { value: Float -> liveSplit = value; onSplitChange(value) }

    // While the handle is held: the pane that would hide if it were released now (split 0 or 1), if any.
    var held by remember { mutableStateOf(false) }
    val hiding = if (held && liveSplit > 0f && liveSplit < 1f) snapSplit(liveSplit).takeIf { it == 0f || it == 1f } else null
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(held) { if (held) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    LaunchedEffect(hiding) { if (hiding != null) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }

    Box(modifier) {
        Column(Modifier.fillMaxSize().onSizeChanged { textHeight = (it.height - dividerHeight).roundToInt() }) {
            Pane(
                label = "Romanian",
                share = liveSplit,
                hiding = hiding == 0f,
                segments = segments,
                transcriptOf = Segment::romanian,
                pane = romanian,
                textScale = textScale,
                placeholder = "Tap the microphone to start. The Romanian transcript appears here.",
            )
            Divider(
                split = liveSplit,
                held = held,
                onHeldChange = { held = it },
                // Panes only collapse on release (snapSplit), so the layout never jumps under the finger.
                onDrag = { delta ->
                    if (textHeight > 0) liveSplit = (liveSplit + delta / textHeight).coerceIn(DRAG_MIN, 1f - DRAG_MIN)
                },
                onDragStopped = { settle(snapSplit(liveSplit)) },
                onSet = settle,
            )
            Pane(
                label = "English",
                share = 1f - liveSplit,
                hiding = hiding == 1f,
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

/**
 * The text, taking [share] of the panes' text height; nothing at a share of 0. It fades out at an
 * edge with more text beyond it. While [hiding], it dims under a "Release to hide" hint.
 */
@Composable
private fun ColumnScope.Pane(
    label: String,
    share: Float,
    hiding: Boolean,
    segments: List<Segment>,
    transcriptOf: (Segment) -> Transcript,
    pane: PaneScroll,
    textScale: Float,
    placeholder: String,
) {
    if (share <= 0f) return
    val alpha by animateFloatAsState(if (hiding) 0.3f else 1f, label = "hiding")
    Box(Modifier.weight(share).fillMaxWidth()) {
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
            if (segments.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                )
            } else {
                val body = MaterialTheme.typography.bodyLarge
                val textStyle = body.copy(fontSize = body.fontSize * textScale, lineHeight = body.lineHeight * textScale)
                val provisional = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
                val content = paneText(segments, transcriptOf, provisional)
                val topFade by animateFloatAsState(if (pane.scroll.canScrollBackward) 1f else 0f, label = "topFade")
                val bottomFade by animateFloatAsState(if (pane.scroll.canScrollForward) 1f else 0f, label = "bottomFade")
                Text(
                    content.text,
                    style = textStyle,
                    modifier = Modifier
                        .fillMaxSize()
                        .fadingEdges({ topFade }, { bottomFade }, FADE_LENGTH)
                        .verticalScroll(pane.scroll),
                    onTextLayout = { result: TextLayoutResult ->
                        pane.layout = PaneLayout(
                            segmentTops = content.segmentStarts.map { result.getLineTop(result.getLineForOffset(it)) },
                            height = result.size.height.toFloat(),
                        )
                    },
                )
            }
        }
        if (hiding) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    "Release to hide $label",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Fades content out over [length] at the top and bottom, by [top] and [bottom] (0 to 1), read when drawing. */
private fun Modifier.fadingEdges(top: () -> Float, bottom: () -> Float, length: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val edge = length.toPx().coerceAtMost(size.height / 2)
            val topFade = top()
            if (topFade > 0f) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 1f - topFade), Color.Black), startY = 0f, endY = edge),
                    size = Size(size.width, edge),
                    blendMode = BlendMode.DstIn,
                )
            }
            val bottomFade = bottom()
            if (bottomFade > 0f) {
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Black, Color.Black.copy(alpha = 1f - bottomFade)),
                        startY = size.height - edge,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - edge),
                    size = Size(size.width, edge),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

/**
 * The bar between the panes: "Română ↑" and "English ↓" labels on a line, with a handle in the
 * middle. Drag anywhere on it to resize, double-tap for an even split; a hidden pane's label brings
 * it back. Accessibility actions offer the same without dragging.
 */
@Composable
private fun Divider(
    split: Float,
    held: Boolean,
    onHeldChange: (Boolean) -> Unit,
    onDrag: (Float) -> Unit,
    onDragStopped: () -> Unit,
    onSet: (Float) -> Unit,
) {
    val setHeld by rememberUpdatedState(onHeldChange)
    val line by animateColorAsState(
        if (held) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
        label = "line",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DIVIDER_HEIGHT)
            // Held from the first touch until every finger lifts; consumes nothing, so dragging and taps still work.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    setHeld(true)
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    setHeld(false)
                }
            }
            .draggable(
                state = rememberDraggableState(onDrag),
                orientation = Orientation.Vertical,
                onDragStopped = { onDragStopped() },
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
        HorizontalDivider(color = line)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            val romanianHidden = split <= 0f
            val englishHidden = split >= 1f
            DividerLabel(
                label = "Română",
                icon = if (romanianHidden) R.drawable.ic_expand_more else R.drawable.ic_arrow_upward,
                color = MaterialTheme.colorScheme.primary,
                onShow = if (romanianHidden) ({ onSet(0.5f) }) else null,
                showLabel = "Show Romanian",
                modifier = Modifier.padding(end = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            Handle(held)
            Spacer(Modifier.weight(1f))
            DividerLabel(
                label = "English",
                icon = if (englishHidden) R.drawable.ic_expand_less else R.drawable.ic_arrow_downward,
                color = MaterialTheme.colorScheme.tertiary,
                onShow = if (englishHidden) ({ onSet(0.5f) }) else null,
                showLabel = "Show English",
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** A pane's name and arrow, on the background so it cuts the line; tappable while that pane is hidden. */
@Composable
private fun DividerLabel(
    label: String,
    @DrawableRes icon: Int,
    color: Color,
    onShow: (() -> Unit)?,
    showLabel: String,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxHeight().background(background), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .then(if (onShow != null) Modifier.clickable(onClickLabel = showLabel, onClick = onShow) else Modifier)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            Icon(painterResource(icon), contentDescription = null, Modifier.size(16.dp), tint = color)
        }
    }
}

/** A small grey pill with ↕ at rest; held, it grows into the accent colour with a halo and ↑ ↓ arrows. */
@Composable
private fun Handle(held: Boolean) {
    val colors = MaterialTheme.colorScheme
    val width by animateDpAsState(if (held) 56.dp else 40.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "width")
    val height by animateDpAsState(if (held) 28.dp else 20.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "height")
    val halo by animateFloatAsState(if (held) 0.18f else 0f, label = "halo")
    val container by animateColorAsState(if (held) colors.primary else colors.surfaceContainerHighest, label = "container")
    val content by animateColorAsState(if (held) colors.onPrimary else colors.outline, label = "content")
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.background(colors.primary.copy(alpha = halo), CircleShape).padding(6.dp)) {
            Box(Modifier.size(width, height).background(container, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_unfold_more), contentDescription = null, Modifier.size(16.dp), tint = content)
            }
        }
        AnimatedVisibility(held, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.offset(y = (-26).dp)) {
            Icon(painterResource(R.drawable.ic_expand_less), contentDescription = null, Modifier.size(14.dp), tint = colors.primary)
        }
        AnimatedVisibility(held, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.offset(y = 26.dp)) {
            Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, Modifier.size(14.dp), tint = colors.primary)
        }
    }
}
