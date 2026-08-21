package com.example.ottomatic.feature.grapheditor.assistant

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ottomatic.R
import com.example.ottomatic.feature.grapheditor.EditorColors

/**
 * The canvas button that opens the assistant, wearing a running border while it works.
 *
 * **The border exists because the panel can be folded away or closed.** A turn goes on
 * running either way — nodes appear on the canvas with nothing on screen saying who is
 * placing them — and this is the one control that is always visible. Without it the honest
 * answer to "is it still going?" is to reopen the panel and look.
 *
 * A *running* border rather than a spinner or a tint, for two reasons. It reads as ongoing
 * rather than as a state, which is what an indeterminate wait is; and it leaves the button
 * itself untouched, so what it does and where you press are exactly what they were.
 */
@Composable
internal fun AssistantFab(busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (busy) RunningBorder()
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = EditorColors.chrome,
            contentColor = EditorColors.valueAccent,
            shape = RoundedCornerShape(FAB_CORNER),
            modifier = Modifier.size(FAB_SIZE),
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = stringResource(R.string.grapheditor_ask_ai))
        }
    }
}

/**
 * A sweep of colour going round the button's edge.
 *
 * **Drawn as a filled shape behind an opaque button rather than as a stroke**, which is
 * what makes the corners hold still. A stroke would have to be rotated to move its
 * gradient, and rotating a rounded rectangle spins the corners with it. Here the clip
 * fixes the silhouette, the fill is rotated *inside* that clip so only the colour travels,
 * and the button's own opaque container covers the middle — leaving exactly a border.
 *
 * The circle is drawn at [BLEED] times the box so no corner is left unpainted at any angle;
 * a rotated circle is geometrically identical to itself, so all the rotation does is carry
 * the sweep gradient round.
 *
 * Composed only while it is wanted, so the infinite animation does not exist at rest.
 */
@Composable
private fun RunningBorder() {
    val spin = rememberInfiniteTransition(label = "assistant-busy")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN,
        animationSpec = infiniteRepeatable(
            // Linear and restarting: a border that eased would read as breathing, which is
            // a different thing from something being underway.
            animation = tween(durationMillis = SPIN_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle",
    )

    Box(
        modifier = Modifier
            .size(FAB_SIZE + BORDER * 2)
            .clip(RoundedCornerShape(FAB_CORNER + BORDER))
            .rotate(angle)
            .drawBehind {
                drawCircle(
                    // Transparent at both ends of the sweep, so the seam where the gradient
                    // wraps is invisible and it reads as one arc chasing its own tail.
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, EditorColors.actionAccent, Color.Transparent),
                    ),
                    radius = size.maxDimension * BLEED,
                )
            },
    )
}

private val FAB_SIZE = 40.dp
private val FAB_CORNER = 12.dp
private val BORDER = 3.dp

private const val FULL_TURN = 360f
private const val SPIN_MS = 1_400

/** Enough overdraw that a rotated circle still covers every corner of the clip. */
private const val BLEED = 1.5f
