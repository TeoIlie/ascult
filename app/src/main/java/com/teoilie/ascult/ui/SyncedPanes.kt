package com.teoilie.ascult.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
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

private val FADE_LENGTH = 32.dp

/** Enough of Soniox's latest English to fill the live caption's two lines at any text size. */
private const val CAPTION_CHARS = 300

/** The live caption's text size relative to the panes' text. */
private const val CAPTION_SCALE = 0.85f

/** Smallest share a pane keeps while the handle is being dragged. */
private const val DRAG_MIN = 0.02f

/** The two panes: Romanian above the divider, English below. [hiddenSplit] is the split that hides the pane. */
internal enum class PaneSide(
    val language: String,
    val label: String,
    val hiddenSplit: Float,
    val transcriptOf: (Segment) -> Transcript,
    val placeholder: String,
) {
    ROMANIAN("Romanian", "Română", 0f, Segment::romanian, "Tap the microphone to start. The Romanian transcript appears here."),
    ENGLISH("English", "English", 1f, Segment::shownEnglish, "The English translation appears here."),
    ;

    /** This pane's share of the text height for a given split (the Romanian share). */
    fun share(split: Float): Float = if (this == ROMANIAN) split else 1f - split
}

@Composable
internal fun PaneSide.color(): Color = when (this) {
    PaneSide.ROMANIAN -> MaterialTheme.colorScheme.primary
    PaneSide.ENGLISH -> MaterialTheme.colorScheme.tertiary
}

/**
 * Romanian above English, scrolled together. While following live, both stick to the end as text
 * arrives. Scrolling either pane stops following and keeps the other aligned by segment: the same
 * fraction through the same segment sits at the centre of both. Reaching the end resumes following.
 *
 * The [PaneDivider] between them sets [split], the Romanian share of the text height. Released near
 * either end, the handle hides that pane; [onSplitChange] gets the settled value to save.
 *
 * With [showLiveCaption] in Claude mode, a dimmed caption under the English shows Soniox's latest
 * English, so there is something to read between Claude's sentences.
 *
 * Without [live] (a saved sermon), the panes open at the top and there is no Jump to live.
 */
@Composable
fun SyncedPanes(
    segments: List<Segment>,
    showLiveCaption: Boolean,
    textScale: Float,
    split: Float,
    onSplitChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = true,
) {
    val scrolls = remember { PaneSide.entries.associateWith { PaneScroll() } }
    var followLive by remember { mutableStateOf(live) }
    var leader by remember { mutableStateOf<PaneScroll?>(null) } // the pane the user last dragged

    for (pane in scrolls.values) {
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
            scrolls.values.first { it !== lead }.scrollToMatch(lead)
        }
    }

    // Follows the handle while dragging; reset whenever the saved value changes.
    var liveSplit by remember(split) { mutableFloatStateOf(split) }
    var textHeight by remember { mutableIntStateOf(0) }
    val dividerHeight = with(LocalDensity.current) { DIVIDER_HEIGHT.toPx() }
    val settle = { value: Float -> liveSplit = value; onSplitChange(value) }

    // While the handle is held: the pane that would hide if it were released now, if any.
    var held by remember { mutableStateOf(false) }
    val hiding = if (held && liveSplit > 0f && liveSplit < 1f) PaneSide.entries.find { it.hiddenSplit == snapSplit(liveSplit) } else null
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(held) { if (held) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    LaunchedEffect(hiding) { if (hiding != null) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }

    @Composable
    fun ColumnScope.PaneFor(side: PaneSide) =
        Pane(
            side,
            side.share(liveSplit),
            hiding = hiding == side,
            segments,
            caption = if (side == PaneSide.ENGLISH) liveCaption(segments, showLiveCaption) else null,
            scrolls.getValue(side),
            textScale,
        )

    Box(modifier) {
        Column(Modifier.fillMaxSize().onSizeChanged { textHeight = (it.height - dividerHeight).roundToInt() }) {
            PaneFor(PaneSide.ROMANIAN)
            PaneDivider(
                hidden = PaneSide.entries.find { it.hiddenSplit == liveSplit },
                held = held,
                onHeldChange = { held = it },
                // Panes only collapse on release (snapSplit), so the layout never jumps under the finger.
                onDrag = { delta ->
                    if (textHeight > 0) liveSplit = (liveSplit + delta / textHeight).coerceIn(DRAG_MIN, 1f - DRAG_MIN)
                },
                onDragStopped = { settle(snapSplit(liveSplit)) },
                onSet = settle,
            )
            PaneFor(PaneSide.ENGLISH)
        }
        if (live && !followLive && segments.isNotEmpty()) {
            FilledTonalButton(
                onClick = { followLive = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Jump to live")
            }
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
 * Soniox's latest English while Claude translates the current segment, trimmed to its last
 * [CAPTION_CHARS] characters at a word; null unless [show] (listening, caption on) and there is
 * such a caption.
 */
internal fun liveCaption(segments: List<Segment>, show: Boolean): String? {
    val segment = segments.lastOrNull() ?: return null
    val claude = segment.claude ?: return null
    if (!show || claude.failed) return null
    val text = (segment.english.final + segment.english.provisional).trim()
    return when {
        text.isEmpty() -> null
        text.length <= CAPTION_CHARS -> text
        else -> "…" + text.takeLast(CAPTION_CHARS).substringAfter(' ')
    }
}

/**
 * The text, taking [share] of the panes' text height; nothing at a share of 0. It fades out at an
 * edge with more text beyond it. While [hiding], it dims under a "Release to hide" hint. A
 * [caption] shows below the text.
 */
@Composable
private fun ColumnScope.Pane(
    side: PaneSide,
    share: Float,
    hiding: Boolean,
    segments: List<Segment>,
    caption: String?,
    pane: PaneScroll,
    textScale: Float,
) {
    if (share <= 0f) return
    val alpha by animateFloatAsState(if (hiding) 0.3f else 1f, label = "hiding")
    Box(Modifier.weight(share).fillMaxWidth()) {
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
            if (segments.isEmpty()) {
                Text(
                    side.placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                )
            } else {
                val body = MaterialTheme.typography.bodyLarge
                val textStyle = remember(body, textScale) {
                    body.copy(fontSize = body.fontSize * textScale, lineHeight = body.lineHeight * textScale)
                }
                val provisionalColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                val provisional = SpanStyle(color = provisionalColor)
                // Rebuilt only when the text changes, not on every frame of a divider drag.
                val content = remember(segments, side, provisional) { paneText(segments, side.transcriptOf, provisional) }
                val topFade = rememberEdgeFade { pane.scroll.canScrollBackward }
                val bottomFade = rememberEdgeFade { pane.scroll.canScrollForward }
                Column(Modifier.fillMaxSize()) {
                    Text(
                        content.text,
                        style = textStyle,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .fadingEdges({ topFade.value }, { bottomFade.value }, FADE_LENGTH)
                            .verticalScroll(pane.scroll),
                        onTextLayout = { result ->
                            pane.layout = PaneLayout(
                                segmentTops = content.segmentStarts.map { result.getLineTop(result.getLineForOffset(it)) },
                                height = result.size.height.toFloat(),
                            )
                        },
                    )
                    if (caption != null) LiveCaption(caption, textStyle, provisionalColor)
                }
            }
        }
        if (hiding) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    "Release to hide ${side.language}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** The last two lines of [text], smaller than the panes' [paneStyle] and in [color]; earlier lines are cut off. */
@Composable
private fun LiveCaption(text: String, paneStyle: TextStyle, color: Color) {
    val style = remember(paneStyle) {
        paneStyle.copy(fontSize = paneStyle.fontSize * CAPTION_SCALE, lineHeight = paneStyle.lineHeight * CAPTION_SCALE)
    }
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(lineHeight * 2).clipToBounds()) {
        Text(
            text,
            style = style,
            color = color,
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart).wrapContentHeight(Alignment.Bottom, unbounded = true),
        )
    }
}

/** 0 to 1, animating towards 1 while [visible] is true; read it when drawing, so changes don't recompose. */
@Composable
private fun rememberEdgeFade(visible: () -> Boolean): Animatable<Float, AnimationVector1D> {
    val fade = remember { Animatable(0f) }
    LaunchedEffect(fade) {
        snapshotFlow(visible).collectLatest { fade.animateTo(if (it) 1f else 0f) }
    }
    return fade
}

/** Fades content out over [length] at the top and bottom, by [top] and [bottom] (0 to 1), read when drawing. */
private fun Modifier.fadingEdges(top: () -> Float, bottom: () -> Float, length: Dp): Modifier =
    graphicsLayer {
        // The fade masks the layer's own pixels, which needs an offscreen buffer, but only while it is drawn.
        compositingStrategy = if (top() > 0f || bottom() > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }.drawWithContent {
        drawContent()
        val topFade = top()
        val bottomFade = bottom()
        if (topFade == 0f && bottomFade == 0f) return@drawWithContent
        val edge = (length.toPx() / size.height).coerceAtMost(0.5f)
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 1f - topFade),
                edge to Color.Black,
                1f - edge to Color.Black,
                1f to Color.Black.copy(alpha = 1f - bottomFade),
            ),
            blendMode = BlendMode.DstIn,
        )
    }
