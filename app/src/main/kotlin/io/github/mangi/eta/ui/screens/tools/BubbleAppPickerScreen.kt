package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import org.json.JSONArray

/**
 * 应用选择页：给通知气泡配白名单 / 黑名单（对应原模块 AppPickerActivity）。
 *
 * [mode] 为 "white" 或 "black"，勾选结果写回 Prefs 的 JSON 数组。
 */
@Composable
internal fun BubbleAppPickerScreen(mode: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs.localAgentPreferences() }
    val key = if (mode == "white") {
        Prefs.Keys.AGENT_BUBBLE_WHITELIST_JSON
    } else {
        Prefs.Keys.AGENT_BUBBLE_BLACKLIST_JSON
    }

    val installed = remember {
        val manager = context.packageManager
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            manager.queryIntentActivities(intent, 0)
                .mapNotNull { info -> info.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .map { info -> info.packageName to manager.getApplicationLabel(info).toString() }
                .sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
    }

    var selected by remember {
        mutableStateOf(
            runCatching {
                val array = JSONArray(prefs?.getString(key, "[]") ?: "[]")
                buildSet { for (index in 0 until array.length()) add(array.optString(index)) }
            }.getOrDefault(emptySet()),
        )
    }

    fun persist(next: Set<String>) {
        selected = next
        val json = JSONArray().apply { next.forEach { put(it) } }.toString()
        prefs?.edit()?.putString(key, json)?.apply()
    }

    CouiScaffoldPage(
        title = if (mode == "white") "白名单应用" else "黑名单应用",
        onBack = onBack,
    ) {
        Group(title = if (mode == "white") "只对这些应用生效" else "这些应用不弹气泡") {
            CouiSwitchPreference(
                title = "全部清空",
                summary = if (selected.isEmpty()) {
                    if (mode == "white") "当前不限制，对所有应用生效" else "当前没有排除任何应用"
                } else {
                    "已选 ${selected.size} 个应用"
                },
                checked = selected.isEmpty(),
                onCheckedChange = { persist(emptySet()) },
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(installed.size) { index ->
                val (packageName, label) = installed[index]
                Column {
                    CouiSwitchPreference(
                        title = label,
                        summary = packageName,
                        checked = packageName in selected,
                        onCheckedChange = { checked ->
                            val next = selected.toMutableSet()
                            if (checked) next.add(packageName) else next.remove(packageName)
                            persist(next)
                        },
                    )
                    Divider()
                }
            }
        }
        CouiPageBottomSpacer()
    }
}
