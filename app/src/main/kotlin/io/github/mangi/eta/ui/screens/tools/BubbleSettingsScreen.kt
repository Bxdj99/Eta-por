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
 * 参数全部对应原模块 BubbleSettingsActivity 的开关：卡片（开关/宽度/自适应/停留/动态时长/
 * 仅贴边）、球（变暗/自动消失/装饰动画/亮屏提示）、交互（下滑清通知）、状态栏（滚动/轮数）。
 */
@Composable
internal fun BubbleSettingsScreen(onBack: () -> Unit) {
    val prefs = remember { Prefs.localAgentPreferences() }

    fun boolOf(key: String, default: Boolean): Boolean =
        prefs?.getBoolean(key, default) ?: default

    fun setBool(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    var enabled by remember { mutableStateOf(boolOf(Prefs.Keys.AGENT_BUBBLE_NOTIFICATIONS, true)) }
    var previewEnabled by remember { mutableStateOf(boolOf(Prefs.Keys.AGENT_BUBBLE_PREVIEW_ENABLED, true)) }
    var autoWidth by remember { mutableStateOf(boolOf(Prefs.Keys.AGENT_BUBBLE_AUTO_WIDTH, false)) }
    var dynamicTimeout by remember { mutableStateOf(boolOf(Prefs.Keys.AGENT_BUBBLE_DYNAMIC_TIMEOUT, true)) }
    var edgeOnly by remember { mutableStateOf(boolOf(Prefs.Keys.AGENT_BUBBLE_EDGE_ONLY, false)) }
    var timeoutDim by remember { mutableStateOf(boolOf(Prefs.Keys.AGENT_BUBBLE_TIMEOUT_DIM, true)) }
    var autoDismiss by remember { mutableStateOf(boolOf(Prefs.Keys.AGENT_BUBBLE_AUTO_DISMISS, false)) }
    var decoration by remember { mutableStateOf(boolOf(Prefs.Keys.AGENT_BUBBLE_DECORATION, true)) }
    var wakeScreen by remember { mutableStateOf(boolOf(Prefs.Keys.AGENT_BUBBLE_WAKE_SCREEN, true)) }
    var clearOnSwipe by remember { mutableStateOf(boolOf(Prefs.Keys.AGENT_BUBBLE_CLEAR_ON_SWIPE, false)) }
    var statusScroll by remember { mutableStateOf(boolOf(Prefs.Keys.AGENT_BUBBLE_STATUS_SCROLL, true)) }

    var widthDraft by remember {
        mutableStateOf(Prefs.getAgentInt(Prefs.Keys.AGENT_BUBBLE_WIDTH_DP, 360).toFloat())
    }
    var timeoutDraft by remember {
        mutableStateOf(Prefs.getAgentInt(Prefs.Keys.AGENT_BUBBLE_TIMEOUT_SEC, 8).toFloat())
    }
    var roundsDraft by remember {
        mutableStateOf(Prefs.getAgentInt(Prefs.Keys.AGENT_BUBBLE_STATUS_ROUNDS, 2).toFloat())
    }

    CouiScaffoldPage(title = "通知气泡", onBack = onBack) {
        Group(title = "通知气泡") {
            CouiSwitchPreference(
                title = "启用通知气泡",
                summary = "通知到达时在屏幕边缘显示悬浮球与内容卡片",
                checked = enabled,
                onCheckedChange = { value ->
                    enabled = value
                    setBool(Prefs.Keys.AGENT_BUBBLE_NOTIFICATIONS, value)
                },
            )
            Divider()
            CouiSwitchPreference(
                title = "显示内容卡片",
                summary = "在球旁边弹出通知标题与正文",
                checked = previewEnabled,
                onCheckedChange = { value ->
                    previewEnabled = value
                    setBool(Prefs.Keys.AGENT_BUBBLE_PREVIEW_ENABLED, value)
                },
            )
        }

        Group(title = "卡片") {
            CouiSliderPreference(
                title = "卡片宽度",
                summary = "最宽不超过屏宽减 20dp",
                value = widthDraft,
                onValueChange = { widthDraft = it },
                valueRange = 180f..360f,
                steps = 6,
                valueText = "${widthDraft.roundToInt()}dp",
                onValueChangeFinished = {
                    Prefs.setBubbleInt(Prefs.Keys.AGENT_BUBBLE_WIDTH_DP, widthDraft.roundToInt())
                },
            )
            Divider()
            CouiSwitchPreference(
                title = "宽度随内容自适应",
                summary = "短消息卡片更窄",
                checked = autoWidth,
                onCheckedChange = { value ->
                    autoWidth = value
                    setBool(Prefs.Keys.AGENT_BUBBLE_AUTO_WIDTH, value)
                },
            )
            Divider()
            CouiSliderPreference(
                title = "卡片停留时长",
                summary = "无操作后自动收起，球保留",
                value = timeoutDraft,
                onValueChange = { timeoutDraft = it },
                valueRange = 2f..15f,
                steps = 13,
                valueText = "${timeoutDraft.roundToInt()} 秒",
                onValueChangeFinished = {
                    Prefs.setBubbleInt(Prefs.Keys.AGENT_BUBBLE_TIMEOUT_SEC, timeoutDraft.roundToInt())
                },
            )
            Divider()
            CouiSwitchPreference(
                title = "时长随文字长度变化",
                summary = "长文本多停一会",
                checked = dynamicTimeout,
                onCheckedChange = { value ->
                    dynamicTimeout = value
                    setBool(Prefs.Keys.AGENT_BUBBLE_DYNAMIC_TIMEOUT, value)
                },
            )
            Divider()
            CouiSwitchPreference(
                title = "仅贴边时显示卡片",
                summary = "球吸附在屏幕边缘时才弹内容卡片",
                checked = edgeOnly,
                onCheckedChange = { value ->
                    edgeOnly = value
                    setBool(Prefs.Keys.AGENT_BUBBLE_EDGE_ONLY, value)
                },
            )
        }

        Group(title = "悬浮球") {
            CouiSwitchPreference(
                title = "静置后变暗",
                summary = "一段时间无操作后球变半透明，触摸恢复",
                checked = timeoutDim,
                onCheckedChange = { value ->
                    timeoutDim = value
                    setBool(Prefs.Keys.AGENT_BUBBLE_TIMEOUT_DIM, value)
                },
            )
            Divider()
            CouiSwitchPreference(
                title = "静置后自动消失",
                summary = "超时后球缩小淡出并清空队列",
                checked = autoDismiss,
                onCheckedChange = { value ->
                    autoDismiss = value
                    setBool(Prefs.Keys.AGENT_BUBBLE_AUTO_DISMISS, value)
                },
            )
            Divider()
            CouiSwitchPreference(
                title = "装饰光晕动画",
                summary = "球静置时的缓慢呼吸缩放",
                checked = decoration,
                onCheckedChange = { value ->
                    decoration = value
                    setBool(Prefs.Keys.AGENT_BUBBLE_DECORATION, value)
                },
            )
            Divider()
            CouiSwitchPreference(
                title = "出现时点亮屏幕",
                summary = "灭屏时来通知先唤醒屏幕",
                checked = wakeScreen,
                onCheckedChange = { value ->
                    wakeScreen = value
                    setBool(Prefs.Keys.AGENT_BUBBLE_WAKE_SCREEN, value)
                },
            )
        }

        Group(title = "状态栏与交互") {
            CouiSwitchPreference(
                title = "下滑时清除状态栏通知",
                summary = "关闭当前通知的同时清空通知栏",
                checked = clearOnSwipe,
                onCheckedChange = { value ->
                    clearOnSwipe = value
                    setBool(Prefs.Keys.AGENT_BUBBLE_CLEAR_ON_SWIPE, value)
                },
            )
            Divider()
            CouiSwitchPreference(
                title = "状态栏滚动显示",
                summary = "在状态栏区域滚动展示通知内容",
                checked = statusScroll,
                onCheckedChange = { value ->
                    statusScroll = value
                    setBool(Prefs.Keys.AGENT_BUBBLE_STATUS_SCROLL, value)
                },
            )
            Divider()
            CouiSliderPreference(
                title = "滚动轮数",
                summary = "每条通知在状态栏滚动的次数",
                value = roundsDraft,
                onValueChange = { roundsDraft = it },
                valueRange = 1f..5f,
                steps = 4,
                valueText = "${roundsDraft.roundToInt()} 轮",
                onValueChangeFinished = {
                    Prefs.setBubbleInt(Prefs.Keys.AGENT_BUBBLE_STATUS_ROUNDS, roundsDraft.roundToInt())
                },
            )
        }

        Footer(
            text = "手势：点击打开应用、下滑关闭当前、上滑看上一条、长按展开通知栈、" +
                "长按后拖动移动、靠边吸附、拖到底部删除。",
        )
        CouiPageBottomSpacer()
    }
}
