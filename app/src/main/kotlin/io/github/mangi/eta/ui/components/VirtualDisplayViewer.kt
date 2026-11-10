package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
                        detectTapGestures(
                            onTap = { offset -> injectTap(offset.x, offset.y, false) },
                            onLongPress = { offset -> injectTap(offset.x, offset.y, true) },
                        )
                    }
                    .pointerInput(viewport) {
                        var start = Offset.Zero
                        var total = Offset.Zero
                        detectDragGestures(
                            onDragStart = { start = it; total = Offset.Zero },
                            onDrag = { change, delta ->
                                change.consume()
                                total += delta
                            },
                            onDragEnd = {
                                val from = normalized(start.x, start.y, clamp = true)
                                val to = normalized(
                                    start.x + total.x,
                                    start.y + total.y,
                                    clamp = true,
                                )
                                if (from != null && to != null) {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching {
                                            VirtualDisplayController.dispatchUserSwipeOnCanvas(
                                                from.first, from.second, to.first, to.second, 320,
                                            )
                                        }
                                    }
                                }
                            },
                        )
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
