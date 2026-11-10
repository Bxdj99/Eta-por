package io.github.mangi.eta.ui.screens.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.automation.AgentTaskTools
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.AgentTaskRunEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 自动任务页：把已经在跑的 tasks_* 能力变成手指能操作的东西。
 *
 * 写操作一律走 [AgentTaskTools]，不在这里重写校验/调度逻辑——
 * 工具层已经做了触发器校验、`nextRunAt` 计算、`AgentTaskScheduler.refresh()` 与防重入，
 * 界面只负责收集参数并把拒绝原因原样回显。
 */
@Composable
internal fun AgentTasksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<AgentTaskEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<TaskDraft?>(null) }
    var historyTarget by remember { mutableStateOf<AgentTaskEntity?>(null) }

    suspend fun reload() {
        tasks = withContext(Dispatchers.IO) {
            runCatching { EtaDatabase.get(context).agentTaskDao().tasks() }.getOrDefault(emptyList())
        }
        loaded = true
    }

    // 工具层返回的 JSON 统一处理：ok=false 时把 message 直接给用户看
    fun runTool(tool: String, args: JSONObject, onDone: (() -> Unit)? = null) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AgentTaskTools(context).execute(tool, args)
            }
            val json = runCatching { JSONObject(result) }.getOrNull()
            if (json?.optBoolean("ok") != true) {
                notice = json?.optString("message")?.takeIf { it.isNotBlank() } ?: "任务操作失败"
            }
            reload()
            onDone?.invoke()
        }
    }

    fun toggle(task: AgentTaskEntity, enabled: Boolean) {
        runTool(
            "tasks_update",
            JSONObject()
                .put("taskId", task.id)
                .put("expectedUpdatedAt", task.updatedAt)
                .put("enabled", enabled),
        )
    }

    LaunchedEffect(Unit) { reload() }

    TasksPage(title = "自动任务", onBack = onBack) {
        val enabledCount = tasks.count { it.enabled }
        val runningCount = tasks.count { it.lastStatus == "queued" || it.lastStatus == "running" }
        Text(
            text = when {
                !loaded -> "读取中…"
                tasks.isEmpty() -> "还没有自动任务。建一个，让它自己跑。"
                else -> "共 ${tasks.size} 个 · 启用 $enabledCount 个" + if (runningCount > 0) " · 运行中 $runningCount" else ""
            },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        )
        notice?.let { message ->
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        tasks.forEach { task ->
            TaskCard(
                task = task,
                onToggle = { enabled -> toggle(task, enabled) },
                onRunNow = {
                    runTool("tasks_run", JSONObject().put("taskId", task.id)) {
                        notice = "已排队，后台稍后开跑"
                    }
                },
                onCancel = {
                    runTool("tasks_cancel", JSONObject().put("taskId", task.id)) {
                        notice = "已请求停止"
                    }
                },
                onHistory = { historyTarget = task },
                onEdit = { draft = task.toDraft() },
                onDelete = {
                    runTool("tasks_delete", JSONObject().put("taskId", task.id))
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = "新建自动任务",
            onClick = { draft = TaskDraft() },
            minWidth = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(28.dp))
    }

    draft?.let { current ->
        TaskEditorDialog(
            draft = current,
            onDraftChange = { draft = it },
            onDismiss = { draft = null },
            onSave = { candidate ->
                runTool(
                    if (candidate.id == null) "tasks_create" else "tasks_update",
                    candidate.toToolArgs(),
                ) { draft = null }
            },
        )
    }

    historyTarget?.let { task ->
        TaskHistoryDialog(task = task, onDismiss = { historyTarget = null })
    }
}

/** 二级页骨架：COUI 用 Coui 的页面壳，Miuix 用自带骨架，内容体共用。 */
@Composable
private fun TasksPage(
    title: String,
    onBack: () -> Unit,
    body: @Composable () -> Unit,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(title = title, onBack = onBack) {
                body()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(title = title, onBack = onBack) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        body()
                    }
                }
            }
        }
    }
}

private data class TaskDraft(
    val id: String? = null,
    val name: String = "",
    val prompt: String = "",
    val kind: String = "daily",
    val time: String = "08:00",
    val weekdays: Set<Int> = emptySet(),
    val intervalMinutes: String = "60",
    val onceDate: String = LocalDate.now().plusDays(1).toString(),
    val onceTime: String = "09:00",
    val enabled: Boolean = true,
    val expectedUpdatedAt: Long = 0L,
)

private fun AgentTaskEntity.toDraft(): TaskDraft {
    val rule = runCatching { JSONObject(triggerJson) }.getOrNull()
    val type = rule?.optString("type").orEmpty()
    val weekdays = rule?.optJSONArray("weekdays")?.let { days ->
        (0 until days.length()).map { days.getInt(it) }.toSet()
    }.orEmpty()
    val onceAt = rule?.optString("at").orEmpty().takeIf { it.isNotBlank() }
    val onceLocal = onceAt?.let {
        runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull()
    }
    return TaskDraft(
        id = id,
        name = name,
        prompt = prompt,
        kind = type.takeIf { it in setOf("daily", "interval", "once") } ?: "daily",
        time = rule?.optString("time")?.takeIf { it.isNotBlank() } ?: "08:00",
        weekdays = weekdays,
        intervalMinutes = rule?.optLong("seconds", 3600)?.div(60)?.toString() ?: "60",
        onceDate = onceLocal?.toLocalDate()?.toString() ?: LocalDate.now().plusDays(1).toString(),
        onceTime = onceLocal?.toLocalTime()?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "09:00",
        enabled = enabled,
        expectedUpdatedAt = updatedAt,
    )
}

private fun TaskDraft.toToolArgs(): JSONObject {
    val args = JSONObject()
        .put("name", name.trim())
        .put("prompt", prompt.trim())
        .put("enabled", enabled)
        .put("trigger", buildTrigger())
    id?.let { args.put("taskId", it).put("expectedUpdatedAt", expectedUpdatedAt) }
    return args
}

private fun TaskDraft.buildTrigger(): JSONObject = when (kind) {
    "interval" -> JSONObject()
        .put("type", "interval")
        .put("seconds", (intervalMinutes.trim().toLongOrNull() ?: 60L) * 60L)

    "once" -> JSONObject()
        .put("type", "once")
        .put("at", onceInstant())

    else -> {
        val rule = JSONObject()
            .put("type", "daily")
            .put("time", time.trim())
            .put("timeZone", ZoneId.systemDefault().id)
        if (weekdays.isNotEmpty()) {
            rule.put("weekdays", JSONArray(weekdays.sorted()))
        }
        rule
    }
}

private fun TaskDraft.onceInstant(): String {
    val date = runCatching { LocalDate.parse(onceDate.trim()) }.getOrDefault(LocalDate.now().plusDays(1))
    val clock = runCatching { LocalTime.parse(onceTime.trim()) }.getOrDefault(LocalTime.of(9, 0))
    return LocalDateTime.of(date, clock).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
}

@Composable
private fun TaskCard(
    task: AgentTaskEntity,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onCancel: () -> Unit,
    onHistory: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = triggerSummary(task.triggerJson),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(checked = task.enabled, onCheckedChange = onToggle)
            }
            Text(
                text = taskMetaLine(task),
                style = MiuixTheme.textStyles.footnote1,
                color = if (task.lastStatus in setOf("failed", "interrupted", "configuration_required")) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (task.lastStatus == "queued" || task.lastStatus == "running") {
                    TextButton(
                        text = "停止",
                        onClick = onCancel,
                        minWidth = 0.dp,
                    )
                } else {
                    TextButton(
                        text = "立即运行",
                        onClick = onRunNow,
                        minWidth = 0.dp,
                    )
                }
                TextButton(
                    text = "历史",
                    onClick = onHistory,
                    minWidth = 0.dp,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    text = "编辑",
                    onClick = onEdit,
                    minWidth = 0.dp,
                )
                TextButton(
                    text = "删除",
                    onClick = onDelete,
                    minWidth = 0.dp,
                )
            }
        }
    }
}

private fun taskMetaLine(task: AgentTaskEntity): String {
    val parts = mutableListOf<String>()
    task.nextRunAt?.let { parts += "下次 " + formatTimestamp(it) }
    parts += "已运行 ${task.runCount} 次"
    if (task.maxRuns in 1..9999) parts += "上限 ${task.maxRuns}"
    val status = when (task.lastStatus) {
        "queued" -> "排队中"
        "running" -> "运行中"
        "completed" -> "上次成功"
        "failed" -> "上次失败"
        "interrupted" -> "上次被打断"
        "configuration_required" -> "需要配置"
        "cancelled" -> "已取消"
        else -> null
    }
    status?.let { parts += it }
    return parts.joinToString(" · ")
}

private fun formatTimestamp(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

/** 把 triggerJson 翻成一句人话；模型建的事件触发也能读出来。 */
private fun triggerSummary(triggerJson: String): String {
    val rule = runCatching { JSONObject(triggerJson) }.getOrNull() ?: return "触发器不可读"
    return when (val type = rule.optString("type")) {
        "daily" -> {
            val time = rule.optString("time")
            val days = rule.optJSONArray("weekdays")?.let { array ->
                (0 until array.length()).map { dayLabel(array.getInt(it)) }
            }.orEmpty()
            if (days.isEmpty()) "每天 $time" else "每周 ${days.joinToString("、")} $time"
        }

        "interval" -> {
            val minutes = rule.optLong("seconds", 0L) / 60L
            when {
                minutes <= 0L -> "按间隔重复"
                minutes % 60L == 0L -> "每 ${minutes / 60L} 小时"
                else -> "每 $minutes 分钟"
            }
        }

        "once" -> "一次性 " + runCatching {
            OffsetDateTime.parse(rule.optString("at")).atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        }.getOrDefault("指定时间")

        else -> eventLabel(type) + filterSuffix(rule)
    }
}

private fun filterSuffix(rule: JSONObject): String {
    val filters = rule.optJSONObject("filters") ?: return ""
    if (filters.length() == 0) return ""
    val parts = mutableListOf<String>()
    filters.optString("packageName").takeIf { it.isNotBlank() }?.let { parts += it }
    filters.optString("titleContains").takeIf { it.isNotBlank() }?.let { parts += "标题含「$it」" }
    filters.optString("textContains").takeIf { it.isNotBlank() }?.let { parts += "内容含「$it」" }
    filters.optString("taskId").takeIf { it.isNotBlank() }?.let { parts += "任务 $it" }
    filters.optString("transport").takeIf { it.isNotBlank() }?.let { parts += it }
    return if (parts.isEmpty()) "" else "（" + parts.joinToString(" · ") + "）"
}

private fun eventLabel(type: String): String = when (type) {
    "notification_posted" -> "收到通知"
    "notification_removed" -> "通知被清除"
    "app_foreground" -> "应用切到前台"
    "device_unlocked" -> "解锁"
    "screen_on" -> "亮屏"
    "screen_off" -> "熄屏"
    "charging_connected" -> "接上电源"
    "charging_disconnected" -> "拔掉电源"
    "battery_low" -> "电量低"
    "battery_okay" -> "电量恢复"
    "network_connected" -> "网络连接"
    "network_disconnected" -> "网络断开"
    "bluetooth_connected" -> "蓝牙连上"
    "bluetooth_disconnected" -> "蓝牙断开"
    "headset_connected" -> "耳机插入"
    "headset_disconnected" -> "耳机拔出"
    "device_boot" -> "开机"
    "memory_updated" -> "记忆更新"
    "task_completed" -> "任务完成"
    "task_failed" -> "任务失败"
    else -> type
}

private fun dayLabel(day: Int): String = when (day) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    else -> "日"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskEditorDialog(
    draft: TaskDraft,
    onDraftChange: (TaskDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: (TaskDraft) -> Unit,
) {
    val isNew = draft.id == null
    val title = if (isNew) "新建自动任务" else "编辑自动任务"
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it)) },
                label = "任务名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = draft.prompt,
                onValueChange = { onDraftChange(draft.copy(prompt = it)) },
                label = "提示词：到点让助手做什么",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "触发方式",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                EtaChoiceChip(text = "每天", selected = draft.kind == "daily", onClick = { onDraftChange(draft.copy(kind = "daily")) })
                EtaChoiceChip(text = "按间隔", selected = draft.kind == "interval", onClick = { onDraftChange(draft.copy(kind = "interval")) })
                EtaChoiceChip(text = "一次性", selected = draft.kind == "once", onClick = { onDraftChange(draft.copy(kind = "once")) })
            }
            Spacer(Modifier.height(8.dp))
            when (draft.kind) {
                "interval" -> TextField(
                    value = draft.intervalMinutes,
                    onValueChange = { onDraftChange(draft.copy(intervalMinutes = it.filter { ch -> ch.isDigit() })) },
                    label = "每隔多少分钟（最少 15）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                "once" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = draft.onceDate,
                        onValueChange = { onDraftChange(draft.copy(onceDate = it)) },
                        label = "日期 yyyy-MM-dd",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextField(
                        value = draft.onceTime,
                        onValueChange = { onDraftChange(draft.copy(onceTime = it)) },
                        label = "时间 HH:mm",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                else -> Column {
                    TextField(
                        value = draft.time,
                        onValueChange = { onDraftChange(draft.copy(time = it)) },
                        label = "时间 HH:mm",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "重复日（不选=每天）",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        (1..7).forEach { day ->
                            val selected = day in draft.weekdays
                            EtaChoiceChip(
                                text = "周" + dayLabel(day),
                                selected = selected,
                                onClick = {
                                    onDraftChange(
                                        draft.copy(
                                            weekdays = if (selected) draft.weekdays - day else draft.weekdays + day,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "启用",
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = draft.enabled, onCheckedChange = { onDraftChange(draft.copy(enabled = it)) })
            }
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiConfirmDialog(
                visible = true,
                title = title,
                message = null,
                confirmText = "保存",
                confirmEnabled = draft.name.isNotBlank() && draft.prompt.isNotBlank(),
                onConfirm = { onSave(draft) },
                onDismissRequest = onDismiss,
            ) {
                Box(modifier = Modifier.heightIn(max = 460.dp)) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) { body() }
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = true,
                cornerRadius = DialogDefaults.CornerRadius,
                title = title,
                onDismissRequest = onDismiss,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.heightIn(max = 460.dp)) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) { body() }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = onDismiss,
                            minWidth = 0.dp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "保存",
                            onClick = { onSave(draft) },
                            minWidth = 0.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskHistoryDialog(task: AgentTaskEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var runs by remember { mutableStateOf<List<AgentTaskRunEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(task.id) {
        runs = withContext(Dispatchers.IO) {
            runCatching { EtaDatabase.get(context).agentTaskDao().runs(task.id, 20) }.getOrDefault(emptyList())
        }
        loading = false
    }
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = triggerSummary(task.triggerJson),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    loading -> Text("读取中…", style = MiuixTheme.textStyles.footnote1)
                    runs.isEmpty() -> Text("还没有运行记录", style = MiuixTheme.textStyles.footnote1)
                    else -> runs.forEach { run ->
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(
                                text = formatTimestamp(run.queuedAt) + " · " + runStatusLabel(run.status),
                                style = MiuixTheme.textStyles.footnote1,
                            )
                            if (run.resultPreview.isNotBlank()) {
                                Text(
                                    text = run.resultPreview,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiNoticeDialog(
                visible = true,
                title = task.name,
                message = null,
                confirmText = stringResource(R.string.action_close),
                onDismissRequest = onDismiss,
            ) { body() }
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = true,
                cornerRadius = DialogDefaults.CornerRadius,
                title = task.name,
                onDismissRequest = onDismiss,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    body()
                    TextButton(
                        text = stringResource(R.string.action_close),
                        onClick = onDismiss,
                        minWidth = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

private fun runStatusLabel(status: String): String = when (status) {
    "queued" -> "排队"
    "running" -> "运行中"
    "completed" -> "成功"
    "failed" -> "失败"
    "interrupted" -> "被打断"
    "cancelled" -> "已取消"
    "configuration_required" -> "需要配置"
    else -> status
}
