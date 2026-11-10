package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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

/**
 * 灵屏悬浮小窗：贴在屏幕边缘实时显示虚拟屏画面，手指可直接点/滑。
 *
 * 与全屏查看器同一套坐标换算（display 尺寸 + FIT_CENTER 留白 → 归一化 0..1），
 * 区别只是窗口尺寸小、带一个可拖动的标题条。
 */
@Composable
internal fun VirtualScreenFloatContent(
    onClose: () -> Unit,
    onOpenFullscreen: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by VirtualDisplayController.snapshots.collectAsState()
    val active = snapshot.active && CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId)
    var boxSize = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
    val viewport = androidx.compose.runtime.remember(boxSize.value, snapshot.width, snapshot.height) {
        VirtualScreenViewport.fit(
            boxSize.value.width,
            boxSize.value.height,
            snapshot.width,
            snapshot.height,
        )
    }

    fun normalized(x: Float, y: Float, clamp: Boolean = false): Pair<Float, Float>? {
        val vp = viewport ?: return null
        val nx = (x - vp.left) / (vp.screenWidth * vp.scale)
        val ny = (y - vp.top) / (vp.screenHeight * vp.scale)
        if (!clamp && (nx !in 0f..1f || ny !in 0f..1f)) return null
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF21A1B1E)),
    ) {
        // 标题条：整条可拖动窗口；右侧两个按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.06f))
                .pointerInput(Unit) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        onDragBy(delta.x, delta.y)
                    }
                }
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
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
            IconButton(onClick = onOpenFullscreen, minWidth = 28.dp, minHeight = 28.dp) {
                Icon(
                    imageVector = Icons.Rounded.OpenInFull,
                    contentDescription = "全屏查看",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onClose, minWidth = 28.dp, minHeight = 28.dp) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭悬浮窗",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { boxSize.value = it }
                .pointerInput(viewport) {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
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
                        val moved = kotlin.math.hypot(
                            (last.x - down.position.x).toDouble(),
                            (last.y - down.position.y).toDouble(),
                        ) > slop
                        if (moved) {
                            val from = normalized(down.position.x, down.position.y, clamp = true)
                            val to = normalized(last.x, last.y, clamp = true)
                            if (from != null && to != null) {
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        VirtualDisplayController.dispatchUserSwipeOnCanvas(
                                            from.first, from.second, to.first, to.second,
                                            duration.coerceAtMost(2000),
                                        )
                                    }
                                }
                            }
                        } else {
                            val point = normalized(down.position.x, down.position.y)
                            if (point != null) {
                                val longPress = duration >= longPressTimeout
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        VirtualDisplayController.dispatchUserTapOnCanvas(
                                            point.first, point.second, longPress,
                                        )
                                    }
                                }
                            }
                        }
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
    }
}
