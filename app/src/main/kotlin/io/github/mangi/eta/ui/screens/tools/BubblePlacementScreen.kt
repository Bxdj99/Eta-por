package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import kotlin.math.roundToInt

/**
 * 摆放位置页：拖动小球选择停靠位置（对应原模块 BubblePlacementActivity）。
 *
 * 松手时把位置换算成百分比写回配置，横竖屏各存一份。
 */
@Composable
internal fun BubblePlacementScreen(onBack: () -> Unit) {
    val density = LocalDensity.current
    val ballSize = 76.dp
    var ballX by remember { mutableFloatStateOf(0f) }
    var ballY by remember { mutableFloatStateOf(0f) }
    var initialized by remember { mutableFloatStateOf(0f) }

    CouiScaffoldPage(title = "摆放位置", onBack = onBack) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val maxX = with(density) { (maxWidth - ballSize).toPx() }.coerceAtLeast(0f)
            val maxY = with(density) { (maxHeight - ballSize).toPx() }.coerceAtLeast(0f)
            if (initialized == 0f && maxX > 0f) {
                initialized = 1f
                val savedX = Prefs.getAgentInt(Prefs.Keys.AGENT_BUBBLE_X_PERCENT, 100)
                val savedY = Prefs.getAgentInt(Prefs.Keys.AGENT_BUBBLE_Y_PERCENT, 35)
                ballX = maxX * savedX / 100f
                ballY = maxY * savedY / 100f
            }
            Box(
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(ballX.roundToInt(), ballY.roundToInt()) }
                    .size(ballSize)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFFF1F9FF), Color(0xFF9FD0EE)),
                        ),
                    )
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = {
                                val xPercent = ((ballX / maxX) * 100f).roundToInt().coerceIn(0, 100)
                                val yPercent = ((ballY / maxY) * 100f).roundToInt().coerceIn(0, 100)
                                Prefs.setBubbleInt(Prefs.Keys.AGENT_BUBBLE_X_PERCENT, xPercent)
                                Prefs.setBubbleInt(Prefs.Keys.AGENT_BUBBLE_Y_PERCENT, yPercent)
                                val dock = when {
                                    ballX <= maxX * 0.08f -> -1
                                    ballX >= maxX * 0.92f -> 1
                                    else -> 0
                                }
                                Prefs.setBubbleInt(Prefs.Keys.AGENT_BUBBLE_DOCK_EDGE, dock)
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            ballX = (ballX + dragAmount.x).coerceIn(0f, maxX)
                            ballY = (ballY + dragAmount.y).coerceIn(0f, maxY)
                        }
                    },
            )
        }
    }
}
