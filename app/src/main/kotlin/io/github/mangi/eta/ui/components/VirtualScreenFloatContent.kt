package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.hypot

/** 悬浮窗需要宿主窗口做的事（由 AgentRuntimeService 实现，负责改 WindowManager 参数）。 */
internal interface VirtualScreenFloatHost {
    fun moveBy(dx: Float, dy: Float)
    fun resizeBy(scale: Float)
    /** 松手后归一：贴边折叠、夹回屏内。 */
    fun settle()
    fun setCollapsed(collapsed: Boolean)
    fun close()
    fun openFullscreen()
}

/**
 * 灵屏悬浮小窗（参照 Oo0520/Eta-por 的 VirtualScreenFloatWindow 手势分区）：
 *
 * - **上边缘中间**（30dp 内、水平居中 60%）：拖动窗口
 * - **四角**（30dp 方区）：等比缩放，最小 120dp、自动夹屏
 * - **下边缘中间**：点一下浮出「关闭」胶囊，再点才真的关
 * - **其余内容区**：点击/长按/滑动直接注入灵屏
 * - **双击内容区**：进全屏查看器
 * - 拖到屏幕左右边缘松手：吸附折叠成细条，点一下展开
 */
@Composable
internal fun VirtualScreenFloatContent(
    host: VirtualScreenFloatHost,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val snapshot by VirtualDisplayController.snapshots.collectAsState()
    val active = snapshot.active && CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId)
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var closeArmed by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(false) }
    val viewport = remember(boxSize, snapshot.width, snapshot.height) {
        VirtualScreenViewport.fit(boxSize.width, boxSize.height, snapshot.width, snapshot.height)
    }

    fun normalized(x: Float, y: Float, clamp: Boolean = false): Pair<Float, Float>? {
        val vp = viewport ?: return null
        val nx = (x - vp.left) / (vp.screenWidth * vp.scale)
        val ny = (y - vp.top) / (vp.screenHeight * vp.scale)
        if (!clamp && (nx !in 0f..1f || ny !in 0f..1f)) return null
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    fun injectTap(point: Offset, longPress: Boolean) {
        val p = normalized(point.x, point.y) ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { VirtualDisplayController.dispatchUserTapOnCanvas(p.first, p.second, longPress) }
        }
    }

    fun injectSwipe(from: Offset, to: Offset, durationMs: Int) {
        val a = normalized(from.x, from.y, clamp = true) ?: return
        val b = normalized(to.x, to.y, clamp = true) ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                VirtualDisplayController.dispatchUserSwipeOnCanvas(
                    a.first, a.second, b.first, b.second, durationMs.coerceAtMost(2000),
                )
            }
        }
    }

    val edgePx = with(density) { EdgeZoneDp.dp.toPx() }

    if (collapsed) {
        // 折叠态：一条窄条，点一下展开，长按可关
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
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.weight(1f))
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF21A1B1E)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.06f))
                .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (active) {
                    snapshot.appName.ifBlank { snapshot.packageName.ifBlank { "灵屏" } }
                } else {
                    "灵屏未运行"
                },
                style = MiuixTheme.textStyles.footnote2,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = host::openFullscreen, minWidth = 26.dp, minHeight = 26.dp) {
                Icon(
                    imageVector = Icons.Rounded.OpenInFull,
                    contentDescription = "全屏查看",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(15.dp),
                )
            }
            IconButton(onClick = host::close, minWidth = 26.dp, minHeight = 26.dp) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭悬浮窗",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { boxSize = it }
                .pointerInput(viewport, active, closeArmed) {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startPos = down.position
                        val zone = zoneOf(startPos, size.width.toFloat(), size.height.toFloat(), edgePx)
                        val startedAt = down.uptimeMillis
                        var last = startPos
                        var total = Offset.Zero
                        var aborted = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (zone != Zone.Content && event.changes.size > 1) {
                                aborted = true
                                break
                            }
                            if (zone == Zone.Content && event.changes.size > 1) {
                                aborted = true
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            total += change.position - change.previousPosition
                            change.consume()
                            last = change.position
                            // 拖动/缩放实时跟随，不等抬手
                            when (zone) {
                                Zone.Drag -> if (change.previousPosition != change.position) {
                                    host.moveBy(
                                        change.position.x - change.previousPosition.x,
                                        change.position.y - change.previousPosition.y,
                                    )
                                }
                                Zone.Corner -> {
                                    val delta = (change.position - change.previousPosition)
                                    val grow = (delta.x + delta.y) / 220f
                                    if (abs(grow) > 0.001f) host.resizeBy(1f + grow)
                                }
                                else -> Unit
                            }
                            if (!change.pressed) break
                        }
                        if (aborted) return@awaitEachGesture
                        val duration = (android.os.SystemClock.uptimeMillis() - startedAt)
                            .toInt().coerceIn(60, 3000)
                        when (zone) {
                            Zone.Drag, Zone.Corner -> {
                                if (total.getDistance() > slop) host.settle()
                            }
                            Zone.Close -> {
                                if (total.getDistance() <= slop) closeArmed = true
                            }
                            Zone.Content -> {
                                if (!active) return@awaitEachGesture
                                val moved = hypot(
                                    (last.x - startPos.x).toDouble(),
                                    (last.y - startPos.y).toDouble(),
                                ) > slop
                                if (moved) {
                                    injectSwipe(startPos, last, duration)
                                } else {
                                    injectTap(startPos, longPress = duration >= longPressTimeout)
                                }
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    // 双击内容区 → 全屏查看器
                    var lastTapAt = 0L
                    detectTapGestures(
                        onTap = { offset ->
                            val now = android.os.SystemClock.uptimeMillis()
                            val inContent = zoneOf(offset, 1f, 1f, 0f) == Zone.Content
                            if (inContent && now - lastTapAt < 280L) {
                                lastTapAt = 0L
                                host.openFullscreen()
                            } else {
                                lastTapAt = now
                            }
                        },
                    )
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
            // 下边缘中间：关闭胶囊（点一下浮出，再点才关）
            if (closeArmed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { host.close() })
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "关闭",
                        color = Color.White,
                        style = MiuixTheme.textStyles.footnote2,
                    )
                }
            }
        }
    }
}

private enum class Zone { Drag, Corner, Close, Content }

private const val EdgeZoneDp = 30f

/** 按命中点判定手势分区；尺寸用控件像素，边角阈值来自 30dp。 */
private fun zoneOf(point: Offset, width: Float, height: Float, edgePx: Float): Zone {
    if (width <= 0f || height <= 0f) return Zone.Content
    val edge = if (edgePx > 0f) edgePx else 1f
    // 四角：30dp 方区
    val atLeft = point.x <= edge
    val atRight = point.x >= width - edge
    val atTop = point.y <= edge
    val atBottom = point.y >= height - edge
    if ((atLeft || atRight) && (atTop || atBottom)) return Zone.Corner
    // 上边缘中间（水平居中 60%）：拖动
    if (atTop && point.x in (width * 0.2f)..(width * 0.8f)) return Zone.Drag
    // 下边缘中间：关闭胶囊
    if (atBottom && point.x in (width * 0.2f)..(width * 0.8f)) return Zone.Close
    return Zone.Content
}
