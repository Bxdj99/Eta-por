package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.display.VirtualScreenViewport
import io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.hypot

/** 悬浮窗宿主：窗口参数由 AgentRuntimeService 改，Compose 只报意图。 */
internal interface VirtualScreenFloatHost {
    fun moveBy(dx: Float, dy: Float)

    /** 双指捏合 / 双击切档用同一个入口：因子缩放（>1 变大）。 */
    fun scaleBy(factor: Float)

    /** 双击在固定档位间循环（fork 的 sizeTiersDp）。 */
    fun cycleTier()

    /** 拖动过程中或松手时贴边：贴到左右边缘就立刻收成气泡。 */
    fun settle(snapNow: Boolean)

    fun setCollapsed(collapsed: Boolean)

    fun close()

    fun openFullscreen()
}

/**
 * 灵屏悬浮小窗（按 Oo0520/Eta-por 的 VirtualScreenFloatWindow 语义实现）：
 *
 * - **无标题栏**：窗口即画面；顶部中间一条 28dp 的拖动条（三个点）负责移动窗口，
 *   长按拖动条 550ms 弹出操作条（全屏 / 关闭）
 * - **拖动即贴边**：拖到左右边缘的瞬间就收成气泡（fork 的 collapseIfAtEdge），不用等松手
 * - **双击**：在 96 / 144 / 200 / 272 dp 四档间循环（fork 的 cycleSizeTier，双击不是全屏）
 * - **双指捏合**：自由缩放，72–420dp
 * - **其余区域**：点击 / 长按 / 滑动注入灵屏
 * - **气泡态**：点击展开、拖动可移动
 */
@Composable
internal fun VirtualScreenFloatContent(host: VirtualScreenFloatHost) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val snapshot by VirtualDisplayController.snapshots.collectAsState()
    val active = snapshot.active && CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId)
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var collapsed by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    val viewport = remember(boxSize, snapshot.width, snapshot.height) {
        VirtualScreenViewport.fit(boxSize.width, boxSize.height, snapshot.width, snapshot.height)
    }
    val handleHeight = with(density) { 28.dp.toPx() }

    fun normalized(x: Float, y: Float, clamp: Boolean = false): Pair<Float, Float>? {
        val vp = viewport ?: return null
        val nx = (x - vp.left) / (vp.screenWidth * vp.scale)
        val ny = (y - vp.top) / (vp.screenHeight * vp.scale)
        if (!clamp && (nx !in 0f..1f || ny !in 0f..1f)) return null
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    if (collapsed) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF21A1B1E))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            collapsed = false
                            host.setCollapsed(false)
                        },
                        onLongPress = { host.close() },
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Rounded.OpenInFull,
                contentDescription = "展开灵屏悬浮窗",
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.weight(1f))
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xF21A1B1E)),
    ) {
        // 画面 + 内容区手势（注入灵屏）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { boxSize = it }
                .pointerInput(viewport, active) {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.position.y <= handleHeight) return@awaitEachGesture
                        val startedAt = down.uptimeMillis
                        var last = down.position
                        var aborted = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.size > 1) {
                                aborted = true
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            last = change.position
                            if (!change.pressed) break
                        }
                        if (aborted || !active) return@awaitEachGesture
                        val duration = (android.os.SystemClock.uptimeMillis() - startedAt)
                            .toInt().coerceIn(60, 3000)
                        val moved = hypot(
                            (last.x - down.position.x).toDouble(),
                            (last.y - down.position.y).toDouble(),
                        ) > slop
                        if (moved) {
                            val a = normalized(down.position.x, down.position.y, clamp = true)
                            val b = normalized(last.x, last.y, clamp = true)
                            if (a != null && b != null) {
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        VirtualDisplayController.dispatchUserSwipeOnCanvas(
                                            a.first, a.second, b.first, b.second,
                                            duration.coerceAtMost(2000),
                                        )
                                    }
                                }
                            }
                        } else {
                            val p = normalized(down.position.x, down.position.y) ?: return@awaitEachGesture
                            val longPress = duration >= longPressTimeout
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    VirtualDisplayController.dispatchUserTapOnCanvas(p.first, p.second, longPress)
                                }
                            }
                        }
                    }
                }
                // 双击切档；双指捏合缩放
                .pointerInput(Unit) {
                    var lastTapAt = 0L
                    var lastTap: Offset? = null
                    detectTapGestures(
                        onTap = { offset ->
                            if (offset.y <= handleHeight) return@detectTapGestures
                            actionsOpen = false
                            val now = android.os.SystemClock.uptimeMillis()
                            val prev = lastTap
                            if (prev != null && now - lastTapAt < 300L &&
                                (prev - offset).getDistance() < with(density) { 20.dp.toPx() }
                            ) {
                                lastTap = null
                                lastTapAt = 0L
                                host.cycleTier()
                            } else {
                                lastTap = offset
                                lastTapAt = now
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (abs(zoom - 1f) > 0.01f) host.scaleBy(zoom)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val frame = snapshot.frame
            if (active && frame != null) {
                Image(
                    bitmap = frame,
                    contentDescription = "灵屏画面",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = if (active) "取帧中…" else "灵屏已结束",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
        }

        // 顶部中间：拖动条（三个点）。拖动=移动窗口，拖动中贴边即收气泡；长按=操作条
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(0.6f)
                .height(28.dp)
                .pointerInput(Unit) {
                    var longPressed = false
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        longPressed = false
                        val startedAt = down.uptimeMillis
                        val startX = down.position.x
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) {
                                val dx = change.position.x - change.previousPosition.x
                                val dy = change.position.y - change.previousPosition.y
                                if (dx != 0f || dy != 0f) {
                                    host.moveBy(dx, dy)
                                    // 拖动中贴边立即折叠（fork 的 collapseIfAtEdge）
                                    host.settle(snapNow = true)
                                }
                                if (!longPressed &&
                                    android.os.SystemClock.uptimeMillis() - startedAt >= 550L &&
                                    (change.position - Offset(startX, down.position.y)).getDistance() < with(density) { 8.dp.toPx() }
                                ) {
                                    longPressed = true
                                    actionsOpen = true
                                }
                            }
                            if (!change.pressed) break
                        }
                        if (!longPressed) host.settle(snapNow = false)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.5f)),
                    )
                }
            }
        }

        // 长按拖动条弹出的操作条
        if (actionsOpen) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(Unit) { detectTapGestures { host.openFullscreen() } }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenInFull,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("全屏", color = Color.White, style = MiuixTheme.textStyles.footnote2)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(Unit) { detectTapGestures { host.close() } }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("关闭", color = Color.White, style = MiuixTheme.textStyles.footnote2)
                }
            }
        }

        // 顶部右侧：状态点（运行中绿、未运行灰）
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(if (active) Color(0xFF3FB98A) else Color.White.copy(alpha = 0.3f)),
        )
        if (!active) {
            Text(
                text = "灵屏未运行",
                style = MiuixTheme.textStyles.footnote2,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp),
            )
        }
    }
}
