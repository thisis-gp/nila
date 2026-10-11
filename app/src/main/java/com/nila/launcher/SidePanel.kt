package com.nila.launcher

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

/** A fling faster than this (px/s) settles the panel in the fling's direction, however far it was dragged. */
internal const val PANEL_FLING_PX_PER_S = 1000f

/** Released slower than a fling, the panel opens once it is this far out. */
internal const val PANEL_OPEN_FRACTION = 0.35f

/** Same feel as the launcher's own pages: quick, with no visible bounce. */
internal val PanelSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** Whether a panel released at [progress] (0 closed, 1 open) with [velocity] (px/s, + towards open) ends open. */
internal fun panelSettlesOpen(progress: Float, velocity: Float): Boolean = when {
    velocity > PANEL_FLING_PX_PER_S -> true
    velocity < -PANEL_FLING_PX_PER_S -> false
    else -> progress > PANEL_OPEN_FRACTION
}

/**
 * Follows a sideways drag: [onDrag] gets each move in px (+ is rightwards), [onEnd] the release velocity in px/s,
 * so a panel can track the finger and then settle instead of switching at a threshold.
 */
internal fun Modifier.sideSwipe(onDrag: (Float) -> Unit, onEnd: (Float) -> Unit): Modifier = pointerInput(Unit) {
    val tracker = VelocityTracker()
    detectHorizontalDragGestures(
        onDragStart = { tracker.resetTracking() },
        onDragEnd = { onEnd(tracker.calculateVelocity().x) },
        onDragCancel = { onEnd(0f) },
    ) { change, dragAmount ->
        tracker.addPosition(change.uptimeMillis, change.position)
        onDrag(dragAmount)
    }
}
