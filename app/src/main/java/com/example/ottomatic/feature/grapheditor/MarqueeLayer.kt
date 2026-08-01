package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke

private const val MARQUEE_STROKE_PX = 2f
private const val MARQUEE_DASH_ON = 10f
private const val MARQUEE_DASH_OFF = 8f

/**
 * Draws the box selection over everything else.
 *
 * Deliberately carries **no** `pointerInput`: a `Canvas` with no pointer modifier
 * is not hit-testable, so putting this on top of the node layer costs nothing in
 * gesture arbitration. It would otherwise sit between the user's finger and the
 * cards it is drawn over.
 *
 * The rect is converted to screen px here rather than being drawn inside a scaled
 * `withTransform`, so the outline keeps a constant on-screen weight. Scaling it
 * with the canvas would leave it nearly invisible at 0.3x — exactly the zoom where
 * a box selection is most useful.
 */
@Composable
fun MarqueeLayer(state: GraphEditorUiState, modifier: Modifier = Modifier) {
    val marquee = state.interaction.marquee ?: return
    val transform = state.transform
    Canvas(modifier = modifier.fillMaxSize()) {
        val scale = transform.scale * density
        val rect = marquee.rect
        val topLeft = Offset(rect.left * scale, rect.top * scale) + transform.offset
        val size = Size(rect.width * scale, rect.height * scale)
        drawRect(color = EditorColors.marqueeFill, topLeft = topLeft, size = size)
        drawRect(
            color = EditorColors.marqueeStroke,
            topLeft = topLeft,
            size = size,
            style = Stroke(
                width = MARQUEE_STROKE_PX * density,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(MARQUEE_DASH_ON, MARQUEE_DASH_OFF)),
            ),
        )
    }
}
