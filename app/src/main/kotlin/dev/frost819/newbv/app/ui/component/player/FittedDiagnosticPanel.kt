package dev.frost819.newbv.app.ui.component.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * Measures a complete diagnostic panel before fitting it into the measured player free area.
 * Scaling the whole panel preserves text/icon proportions and avoids unreachable scroll content
 * on TV. The natural size uses the current UI density; fitting only reduces it when necessary.
 */
@Composable
internal fun FittedDiagnosticPanel(
    modifier: Modifier = Modifier,
    naturalWidth: Dp = 350.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = { Box { content() } }, modifier = modifier) { measurables, constraints ->
        val panel = measurables.single().measure(Constraints.fixedWidth(naturalWidth.roundToPx().coerceAtLeast(1)))
        val scale =
            minOf(
                1f,
                constraints.maxWidth.toFloat() / panel.width.coerceAtLeast(1),
                constraints.maxHeight.toFloat() / panel.height.coerceAtLeast(1),
            )
        val width = constraints.constrainWidth(ceil(panel.width * scale).toInt())
        val height = constraints.constrainHeight(ceil(panel.height * scale).toInt())
        layout(width, height) {
            panel.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}
