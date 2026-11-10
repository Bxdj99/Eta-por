package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 灵屏（后台虚拟屏）面板：看它现在有没有在跑、跑的是哪个应用、画幅多大，
 * 并且给两个直达动作 ——「搬到主屏看」（把灵屏里的应用整块挪到主屏，肉眼可见）
 * 和「关闭灵屏」（跳过 4 分钟宽限，立刻拆屏）。
 */
@Composable
internal fun VirtualDisplayPanelDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by VirtualDisplayController.snapshots.collectAsState()
    var notice by remember { mutableStateOf<String?>(null) }
    var viewerOpen by remember { mutableStateOf(false) }
    val active = snapshot.active && CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId)
    val closeText = stringResource(R.string.action_close)

    val present: () -> Unit = {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { VirtualDisplayController.handoffToMainDisplay(context) }.getOrDefault(false)
            }
            notice = if (ok) "已把灵屏里的应用搬到主屏，去主屏看" else "没能搬过去：灵屏可能已经结束"
        }
    }
    val closeDisplay: () -> Unit = {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { VirtualDisplayController.closeNow(context) }
            }
            notice = "已关闭灵屏"
        }
    }

    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            PanelLine("状态", if (active) "运行中 · display ${snapshot.displayId}" else "未运行")
            PanelLine("应用", snapshot.packageName.ifBlank { if (active) "（尚未打开应用）" else "—" })
            if (active) PanelLine("画幅", "${snapshot.width} × ${snapshot.height}")
            if (active && snapshot.appName.isNotBlank()) PanelLine("名称", snapshot.appName)
            notice?.let { message ->
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (active) {
                TextButton(
                    text = "打开灵屏画面（可触摸操作）",
                    onClick = { viewerOpen = true },
                    minWidth = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = "搬到主屏看",
                        onClick = present,
                        minWidth = 0.dp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "关闭灵屏",
                        onClick = closeDisplay,
                        minWidth = 0.dp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiNoticeDialog(
                visible = true,
                title = "灵屏（后台虚拟屏）",
                message = null,
                confirmText = closeText,
                onDismissRequest = onDismiss,
            ) { body() }
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = true,
                cornerRadius = DialogDefaults.CornerRadius,
                title = "灵屏（后台虚拟屏）",
                onDismissRequest = onDismiss,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    body()
                    TextButton(
                        text = closeText,
                        onClick = onDismiss,
                        minWidth = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
            }
        }
    }

    VirtualDisplayViewer(visible = viewerOpen, onDismiss = { viewerOpen = false })
}

@Composable
private fun PanelLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1,
            maxLines = 2,
        )
    }
}
