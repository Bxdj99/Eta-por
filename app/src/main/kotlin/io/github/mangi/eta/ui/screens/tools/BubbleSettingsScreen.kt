package io.github.mangi.eta.ui.screens.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Group
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSliderPreference
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import kotlin.math.roundToInt

/**
 * 通知气泡设置页。
 *
 * 视觉参数照着原 LSPosed 模块（Bubble Notifications）的可调项来：
 * 宽度对应 content_preview_width_dp，停留时长对应 content_preview_timeout，
 * 不透明度对应 content_preview_overlay_alpha。
 */
@Composable
internal fun BubbleSettingsScreen(onBack: () -> Unit) {
    val prefs = remember { Prefs.localAgentPreferences() }
    var enabled by remember {
        mutableStateOf(prefs?.getBoolean(Prefs.Keys.AGENT_BUBBLE_NOTIFICATIONS, true) ?: true)
    }
    var widthDraft by remember {
        mutableStateOf(Prefs.getAgentInt(Prefs.Keys.AGENT_BUBBLE_WIDTH_DP, 360).toFloat())
    }
    var timeoutDraft by remember {
        mutableStateOf(Prefs.getAgentInt(Prefs.Keys.AGENT_BUBBLE_TIMEOUT_SEC, 8).toFloat())
    }
    var opacityDraft by remember {
        mutableStateOf(Prefs.getAgentInt(Prefs.Keys.AGENT_BUBBLE_OPACITY_PCT, 100).toFloat())
    }

    CouiScaffoldPage(title = "通知气泡", onBack = onBack) {
        Group(title = "气泡") {
            CouiSwitchPreference(
                title = "启用通知气泡",
                summary = "通知到达时在屏幕上弹气泡，点击打开应用",
                checked = enabled,
                onCheckedChange = { value ->
                    enabled = value
                    prefs?.edit()
                        ?.putBoolean(Prefs.Keys.AGENT_BUBBLE_NOTIFICATIONS, value)
                        ?.apply()
                },
            )
            Divider()
            CouiSliderPreference(
                title = "气泡宽度",
                summary = "最宽不超过屏宽减 20dp",
                value = widthDraft,
                onValueChange = { widthDraft = it },
                valueRange = 240f..360f,
                steps = 5,
                valueText = "${widthDraft.roundToInt()}dp",
                onValueChangeFinished = {
                    Prefs.setAgentInt(Prefs.Keys.AGENT_BUBBLE_WIDTH_DP, widthDraft.roundToInt())
                },
            )
            Divider()
            CouiSliderPreference(
                title = "停留时长",
                summary = "无操作后自动淡出",
                value = timeoutDraft,
                onValueChange = { timeoutDraft = it },
                valueRange = 3f..20f,
                steps = 8,
                valueText = "${timeoutDraft.roundToInt()} 秒",
                onValueChangeFinished = {
                    Prefs.setAgentInt(Prefs.Keys.AGENT_BUBBLE_TIMEOUT_SEC, timeoutDraft.roundToInt())
                },
            )
            Divider()
            CouiSliderPreference(
                title = "背景不透明度",
                summary = "越低越透，能看清后面的内容",
                value = opacityDraft,
                onValueChange = { opacityDraft = it },
                valueRange = 40f..100f,
                steps = 5,
                valueText = "${opacityDraft.roundToInt()}%",
                onValueChangeFinished = {
                    Prefs.setAgentInt(Prefs.Keys.AGENT_BUBBLE_OPACITY_PCT, opacityDraft.roundToInt())
                },
            )
        }
        Footer(text = "气泡由 Eta 自己绘制，不需要额外常驻应用。手势：点击打开应用、上滑看上一条、下滑关闭、长按收成悬浮球。")
        CouiPageBottomSpacer()
    }
}
