package com.teoilie.ascult.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.teoilie.ascult.R
import com.teoilie.ascult.settings.EVEN_SPLIT

internal val DIVIDER_HEIGHT = 40.dp

/** Snaps a split dragged most of the way to either end shut, collapsing the smaller pane. */
internal fun snapSplit(split: Float): Float = when {
    split < 0.15f -> 0f
    split > 0.85f -> 1f
    else -> split
}

/**
 * The bar between the panes: "Română ↑" and "English ↓" labels on a line, with a handle in the
 * middle. Drag anywhere on it to resize, double-tap for an even split; the [hidden] pane's label
 * brings it back. [held] is reported from the first touch until every finger lifts, before any drag
 * starts. Accessibility actions offer the same without dragging.
 */
@Composable
internal fun PaneDivider(
    hidden: PaneSide?,
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
            // Consumes nothing, so dragging and double-taps still reach the modifiers below.
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
            .pointerInput(onSet) { detectTapGestures(onDoubleTap = { onSet(EVEN_SPLIT) }) }
            .semantics {
                contentDescription = "Divider between Romanian and English"
                customActions = listOf(CustomAccessibilityAction("Even split") { onSet(EVEN_SPLIT); true }) +
                    PaneSide.entries.map { side -> CustomAccessibilityAction("Hide ${side.language}") { onSet(side.hiddenSplit); true } }
            },
        contentAlignment = Alignment.Center,
    ) {
        HorizontalDivider(color = line)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            DividerLabel(PaneSide.ROMANIAN, hidden == PaneSide.ROMANIAN, onShow = { onSet(EVEN_SPLIT) }, Modifier.padding(end = 6.dp))
            Spacer(Modifier.weight(1f))
            Handle(held)
            Spacer(Modifier.weight(1f))
            DividerLabel(PaneSide.ENGLISH, hidden == PaneSide.ENGLISH, onShow = { onSet(EVEN_SPLIT) }, Modifier.padding(start = 6.dp))
        }
    }
}

/**
 * A pane's name with an arrow pointing at it, on the background so it cuts the line. While that pane
 * is [hidden] the arrow points the way it will open, and tapping the label shows it.
 */
@Composable
private fun DividerLabel(side: PaneSide, hidden: Boolean, onShow: () -> Unit, modifier: Modifier = Modifier) {
    val color = side.color()
    val icon = when (side) {
        PaneSide.ROMANIAN -> if (hidden) R.drawable.ic_expand_more else R.drawable.ic_arrow_upward
        PaneSide.ENGLISH -> if (hidden) R.drawable.ic_expand_less else R.drawable.ic_arrow_downward
    }
    Box(modifier.fillMaxHeight().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .then(if (hidden) Modifier.clickable(onClickLabel = "Show ${side.language}", onClick = onShow) else Modifier)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(side.label, style = MaterialTheme.typography.labelLarge, color = color)
            Icon(painterResource(icon), contentDescription = null, Modifier.size(16.dp), tint = color)
        }
    }
}

/** ↑ above and ↓ below the handle while it is held. */
private val HANDLE_ARROWS = listOf((-26).dp to R.drawable.ic_expand_less, 26.dp to R.drawable.ic_expand_more)

/** A small grey pill with ↕ at rest; held, it grows into the accent colour with a halo and ↑ ↓ arrows. */
@Composable
private fun Handle(held: Boolean) {
    val colors = MaterialTheme.colorScheme
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val transition = updateTransition(held, label = "handle")
    val width by transition.animateDp({ spatial }, label = "width") { if (it) 56.dp else 40.dp }
    val height by transition.animateDp({ spatial }, label = "height") { if (it) 28.dp else 20.dp }
    val halo by transition.animateFloat(label = "halo") { if (it) 0.18f else 0f }
    val container by transition.animateColor(label = "container") { if (it) colors.primary else colors.surfaceContainerHighest }
    val content by transition.animateColor(label = "content") { if (it) colors.onPrimary else colors.outline }
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.background(colors.primary.copy(alpha = halo), CircleShape).padding(6.dp)) {
            Box(Modifier.size(width, height).background(container, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_unfold_more), contentDescription = null, Modifier.size(16.dp), tint = content)
            }
        }
        for ((offset, icon) in HANDLE_ARROWS) {
            AnimatedVisibility(held, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.offset(y = offset)) {
                Icon(painterResource(icon), contentDescription = null, Modifier.size(14.dp), tint = colors.primary)
            }
        }
    }
}
