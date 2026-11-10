package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 * 灵屏实况查看器（全屏）：显示虚拟屏当前画面，并把手指动作按 FIT_CENTER 映射注入到虚拟屏。
 *
 * 打开时接管控制权（`setUserControlling(true)` + 高频帧），关闭时交还给模型。
 * 坐标映射用 [VirtualScreenViewport]，与运行时注入用户手势用的是同一套数学，避免"点偏"。
 */
@Composable
internal fun VirtualDisplayViewer(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by VirtualDisplayController.snapshots.collectAsState()
    val active = snapshot.active && CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var notice by remember { mutableStateOf<String?>(null) }
    var textOpen by remember { mutableStateOf(false) }
    val viewport = remember(canvasSize, snapshot.width, snapshot.height) {
        VirtualScreenViewport.fit(
            canvasSize.width,
            canvasSize.height,
            snapshot.width,
            snapshot.height,
        )
    }

    // 屏没了就自动退出，别留一个黑窗（不能在组合期直接调 onDismiss）
    LaunchedEffect(active) {
        if (!active) onDismiss()
    }
    if (!active) return

    DisposableEffect(snapshot.displayId) {
        VirtualDisplayController.keepAlive()
        VirtualDisplayController.setStageExpanded(true)
        VirtualDisplayController.setUserControlling(true)
        onDispose {
            VirtualDisplayController.setUserControlling(false)
            VirtualDisplayController.setStageExpanded(false)
        }
    }

    /**
     * 画布局部坐标 → 归一化（0..1）。`dispatchUserTapOnCanvas` / `dispatchUserSwipeOnCanvas`
     * 收的是归一化值，再由控制器换算成虚拟屏像素；这里按 FIT_CENTER 的留白先扣掉 offset。
     */
    fun normalized(x: Float, y: Float, clamp: Boolean = false): Pair<Float, Float>? {
        val vp = viewport ?: return null
        val nx = (x - vp.left) / (vp.screenWidth * vp.scale)
        val ny = (y - vp.top) / (vp.screenHeight * vp.scale)
        if (!clamp && (nx !in 0f..1f || ny !in 0f..1f)) return null
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    fun injectTap(x: Float, y: Float, longPress: Boolean) {
        val point = normalized(x, y) ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                VirtualDisplayController.dispatchUserTapOnCanvas(point.first, point.second, longPress)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = snapshot.appName.ifBlank { snapshot.packageName.ifBlank { "灵屏" } },
                        style = MiuixTheme.textStyles.body1,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "display ${snapshot.displayId} · ${snapshot.width}×${snapshot.height} · 触摸已接管",
                        style = MiuixTheme.textStyles.footnote2,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                }
                IconButton(
                    onClick = { textOpen = true },
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Keyboard,
                        contentDescription = "输入文字",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val ok = runCatching { VirtualDisplayController.handoffToMainDisplay(context) }
                                .getOrDefault(false)
                            notice = if (ok) "已搬到主屏" else "搬不过去"
                        }
                    },
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenInFull,
                        contentDescription = "搬到主屏",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onDismiss,
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭查看器",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(viewport) {
                        // 参考 fork 的 TakeoverHostView：按下/抬起两端做判定，
                        // 滑动时长用真实 eventTime 差，长按在抬起时按时长判定，比 tap/drag 两个检测器打架更稳。
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
                            if (aborted) return@awaitEachGesture
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
                                injectTap(
                                    down.position.x,
                                    down.position.y,
                                    longPress = duration >= longPressTimeout,
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val frame = snapshot.frame
                if (frame != null) {
                    Image(
                        bitmap = frame,
                        contentDescription = "灵屏画面",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = "正在取帧…",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
            }
            if (textOpen) {
                var input by remember { mutableStateOf("") }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        textStyle = MiuixTheme.textStyles.body1.copy(color = Color.White),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    top.yukonga.miuix.kmp.basic.TextButton(
                        text = "发送",
                        minWidth = 0.dp,
                        onClick = {
                            val payload = input
                            input = ""
                            textOpen = false
                            scope.launch(Dispatchers.IO) {
                                runCatching { VirtualDisplayController.dispatchUserTextOnCanvas(payload) }
                            }
                        },
                    )
                }
            }
            notice?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = message,
                        color = Color.White,
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
            }
        }
    }
}
