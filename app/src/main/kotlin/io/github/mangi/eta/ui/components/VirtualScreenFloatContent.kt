package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.mangi.eta.agent.display.VirtualScreenViewport
import io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.hypot

/** 悬浮窗宿主：窗口参数由 AgentRuntimeService 改，Compose 只报意图。 */
internal interface VirtualScreenFloatHost {
    fun moveBy(dx: Float, dy: Float)

    /** 边缘拖动缩放：factor > 1 变大，1 以下变小（内部按 72–420dp 夹取）。 */
    fun scaleBy(factor: Float)

    /** 松手后归一：夹回屏内、贴边收气泡。 */
    fun settle(snapNow: Boolean)

    fun setCollapsed(collapsed: Boolean)

    fun close()

    fun openFullscreen()
}

/**
 * 灵屏悬浮小窗：
 *
 * - **顶部中间三个点（28dp）**：拖动窗口
 * - **四边 / 四角（20dp 带）**：拖动缩放（左右改宽、上下改高、四角等比）—— 不再用双击切换档位
 * - **其余内容区**：点击 / 长按 / 滑动注入灵屏
 * - **双指捏合**：也能缩放
 * - 拖到左右边缘：收成 35dp 方形气泡（图标取被投屏应用），点一下展开
 * - **长按三点点**：弹出操作条（全屏 / 关闭）
 */
@Composable
internal fun VirtualScreenFloatContent(host: VirtualScreenFloatHost) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val snapshot by VirtualDisplayController.snapshots.collectAsState()
    val active = snapshot.active && CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId)
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var actionsOpen by remember { mutableStateOf(false) }
    // 边缘/四角拖动时先只做"视觉预览"，手指抬起才真正改窗口尺寸：
    // 拖动过程中反复 updateViewLayout 会让 ColorOS 把这次触摸取消掉（"每滑一下就断"）。
    var previewScale by remember { mutableStateOf(1f) }
    val viewport = remember(boxSize, snapshot.width, snapshot.height) {
        VirtualScreenViewport.fit(boxSize.width, boxSize.height, snapshot.width, snapshot.height)
    }
    val bubblePx = with(density) { 35.dp.toPx() }
    val collapsed = boxSize.width in 1..(bubblePx + 6f).toInt()
    val edgePx = with(density) { 20.dp.toPx() }
    // 拖动手柄与操作条都要跟着窗口一起缩：窗口被拖到 72dp 时，固定 28dp 的手柄和
    // 整条「全屏 / 关闭」会把画面挤没了，甚至互相盖住。这里按窗口短边算一个比例。
    val shortSideDp = with(density) {
        (minOf(boxSize.width, boxSize.height).takeIf { it > 0 } ?: boxSize.height).toDp().value
    }
    val handleDp = (shortSideDp * 0.24f).coerceIn(16f, 28f).dp
    val handlePx = with(density) { handleDp.toPx() }
    val dotDp = (shortSideDp * 0.035f).coerceIn(2.5f, 4f).dp

    fun normalized(x: Float, y: Float, clamp: Boolean = false): Pair<Float, Float>? {
        val vp = viewport ?: return null
        val nx = (x - vp.left) / (vp.screenWidth * vp.scale)
        val ny = (y - vp.top) / (vp.screenHeight * vp.scale)
        if (!clamp && (nx !in 0f..1f || ny !in 0f..1f)) return null
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    fun zoneOf(point: Offset, width: Float, height: Float): Zone {
        if (width <= 0f || height <= 0f) return Zone.Content
        if (point.y <= handlePx && point.x in (width * 0.2f)..(width * 0.8f)) return Zone.Handle
        val atLeft = point.x <= edgePx
        val atRight = point.x >= width - edgePx
        val atTop = point.y <= edgePx
        val atBottom = point.y >= height - edgePx
        if ((atLeft || atRight) && (atTop || atBottom)) return Zone.Corner
        if (atLeft || atRight) return Zone.EdgeHorizontal
        if (atTop || atBottom) return Zone.EdgeVertical
        return Zone.Content
    }

    Box(modifier = Modifier.fillMaxSize().onSizeChanged { boxSize = it }) {
        if (collapsed) {
            val icon = remember(snapshot.packageName) {
                runCatching {
                    val pkg = snapshot.packageName.ifBlank { context.packageName }
                    context.packageManager.getApplicationIcon(pkg)?.toBitmap()?.asImageBitmap()
                }.getOrNull()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xF21A1B1E))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var moved = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.pressed) {
                                    val dx = change.position.x - change.previousPosition.x
                                    val dy = change.position.y - change.previousPosition.y
                                    if (dx != 0f || dy != 0f) {
                                        host.moveBy(dx, dy)
                                        moved = true
                                    }
                                }
                                if (!change.pressed) break
                            }
                            if (!moved) host.setCollapsed(false)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = snapshot.appName.ifBlank { "灵屏" },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(5.dp)
                            .clip(RoundedCornerShape(50)),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.OpenInFull,
                        contentDescription = "展开灵屏悬浮窗",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            return@Box
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xF21A1B1E))
                .pointerInput(viewport, active, boxSize) {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        val zone = zoneOf(down.position, width, height)
                        val startedAt = down.uptimeMillis
                        var last = down.position
                        var aborted = false
                        var longPressed = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.size > 1) {
                                aborted = true
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val dx = change.position.x - change.previousPosition.x
                            val dy = change.position.y - change.previousPosition.y
                            change.consume()
                            when (zone) {
                                Zone.Handle -> {
                                    if (dx != 0f || dy != 0f) host.moveBy(dx, dy)
                                    if (!longPressed &&
                                        android.os.SystemClock.uptimeMillis() - startedAt >= 550L &&
                                        (change.position - down.position).getDistance() < with(density) { 8.dp.toPx() }
                                    ) {
                                        longPressed = true
                                        actionsOpen = true
                                    }
                                }

                                Zone.EdgeHorizontal -> {
                                    if (dx != 0f) {
                                        previewScale = (previewScale * (1f + dx / width.coerceAtLeast(1f)))
                                            .coerceIn(0.4f, 2.5f)
                                    }
                                }

                                Zone.EdgeVertical -> {
                                    if (dy != 0f) {
                                        previewScale = (previewScale * (1f + dy / height.coerceAtLeast(1f)))
                                            .coerceIn(0.4f, 2.5f)
                                    }
                                }

                                Zone.Corner -> {
                                    val delta = dx + dy
                                    if (delta != 0f) {
                                        previewScale = (previewScale * (1f + delta / (width + height).coerceAtLeast(1f)))
                                            .coerceIn(0.4f, 2.5f)
                                    }
                                }

                                Zone.Content -> Unit
                            }
                            last = change.position
                            if (!change.pressed) break
                        }
                        if (aborted) return@awaitEachGesture
                        val duration = (android.os.SystemClock.uptimeMillis() - startedAt)
                            .toInt().coerceIn(60, 3000)
                        when (zone) {
                            Zone.EdgeHorizontal, Zone.EdgeVertical, Zone.Corner -> {
                                if (kotlin.math.abs(previewScale - 1f) > 0.01f) {
                                    host.scaleBy(previewScale)
                                }
                                previewScale = 1f
                                host.settle(snapNow = false)
                            }

                            Zone.Handle -> {
                                // 轻点手柄 = 开/关操作条，免得它一直挡着画面
                                val tapped = (last - down.position).getDistance() < slop
                                if (tapped && !longPressed) actionsOpen = !actionsOpen
                                host.settle(snapNow = false)
                            }

                            Zone.Content -> {
                                if (!active) return@awaitEachGesture
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
                                    val p = normalized(down.position.x, down.position.y)
                                        ?: return@awaitEachGesture
                                    val longPress = duration >= longPressTimeout
                                    scope.launch(Dispatchers.IO) {
                                        runCatching {
                                            VirtualDisplayController.dispatchUserTapOnCanvas(
                                                p.first, p.second, longPress,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // 双指捏合也能缩放
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom > 1.02f || zoom < 0.98f) host.scaleBy(zoom)
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
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = previewScale
                            scaleY = previewScale
                        },
                )
            } else {
                Text(
                    text = if (active) "取帧中…" else "灵屏已结束",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
        }

        // 顶部中间：拖动条（尺寸随窗口缩放，见 handleDp）
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize(0.6f)
                .height(handleDp),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(dotDp * 0.75f)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(dotDp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.55f)),
                    )
                }
            }
        }

        if (actionsOpen) {
            // 窗口被拖小以后，固定尺寸的操作条会盖掉整条画面、两个按钮还互相挤。
            // 这里让操作条按窗口宽度整体缩放；窄到放不下文字时只留图标。
            val showLabels = boxSize.width >= with(density) { 176.dp.toPx() }
            val baseWidthPx = with(density) { (if (showLabels) 152.dp else 84.dp).toPx() }
            val menuScale = if (baseWidthPx > 0f) {
                (boxSize.width / baseWidthPx).coerceIn(0.6f, 1f)
            } else {
                1f
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = handleDp + 2.dp)
                    .graphicsLayer {
                        scaleX = menuScale
                        scaleY = menuScale
                    }
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
                        contentDescription = "全屏",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    if (showLabels) {
                        Spacer(Modifier.width(4.dp))
                        Text("全屏", color = Color.White, style = MiuixTheme.textStyles.footnote2)
                    }
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
                        contentDescription = "关闭",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    if (showLabels) {
                        Spacer(Modifier.width(4.dp))
                        Text("关闭", color = Color.White, style = MiuixTheme.textStyles.footnote2)
                    }
                }
            }
        }

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

private enum class Zone { Handle, EdgeHorizontal, EdgeVertical, Corner, Content }
