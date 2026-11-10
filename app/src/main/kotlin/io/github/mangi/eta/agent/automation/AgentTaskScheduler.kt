package io.github.mangi.eta.agent.automation

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.AgentNotificationHistoryService
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.EtaTrace
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.AgentTaskRunEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal object AgentTaskScheduler {
    private const val JOB_ID = 1110
    private val eventWorker = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(100),
        { runnable -> Thread(runnable, "eta-trigger-events").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy()
    )
    private val cancellationWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            runnable,
            "eta-task-cancel"
        ).apply { isDaemon = true }
    }
    private val activeRuns = ConcurrentHashMap<String, String>()
    private val jobRunning = AtomicBoolean()
    private val jobLock = Any()

    fun jobStarted(): Boolean = synchronized(jobLock) { jobRunning.compareAndSet(false, true) }
    fun jobFinished() = synchronized(jobLock) {
        jobRunning.set(false)
    }

    fun isRegistered(runId: String): Boolean = activeRuns.containsKey(runId)
    fun register(runId: String, taskId: String) {
        activeRuns[runId] = taskId
    }

    fun unregister(runId: String) {
        activeRuns.remove(runId)
    }

    fun cancelRunning(context: Context, taskId: String) {
        activeRuns.filterValues { it == taskId }.keys.forEach { id ->
            cancellationWorker.execute {
                AgentRuntimeClient(
                    context,
                    AndroidAgentLogger
                ).cancelRun(id)
            }
        }
    }

    // 低耗电策略：不常驻、不持锁、不轮询。只在「用户/系统已经在产生事件」时（亮屏、解锁、
    // 前台切换、收到通知、闹钟、开机）顺手查一次到期任务，有就直接在进程内跑掉。
    // 这样不额外耗电，也不会被深睡策略卡住——只要有动静，过期任务几秒内就补跑。
    private val lastOpportunistic = java.util.concurrent.atomic.AtomicLong(0)

    fun opportunisticRun(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastOpportunistic.get() < 20_000L) return
        if (!lastOpportunistic.compareAndSet(lastOpportunistic.get(), now)) return
        val app = context.applicationContext
        eventWorker.execute {
            runBlocking {
                runCatching {
                    val dao = EtaDatabase.get(app).agentTaskDao()
                    enqueueDue(app)
                    val overdue = dao.nextQueued() != null
                    EtaTrace.log("task.opportunistic", "有动静，检查到期任务 overdue=$overdue")
                    if (overdue) {
                        val outcome = AgentTaskRunner.runOnce(app)
                        EtaTrace.log("task.opportunistic.run", outcome.javaClass.simpleName)
                    }
                    refresh(app)
                }.onFailure { AndroidAgentLogger.warn("opportunistic automation failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    fun refresh(context: Context) = runBlocking {
        val dao = EtaDatabase.get(context).agentTaskDao()
        val tasks = dao.tasks().filter { it.enabled && it.runCount < it.maxRuns }
        val now = System.currentTimeMillis()
        val currentState = state(context)
        val earliest = tasks.mapNotNull { task ->
            task.nextRunAt?.let { due ->
                if (due <= now && !AgentTaskRules.conditionsMatch(
                        JSONObject(task.triggerJson),
                        currentState
                    )
                ) now + 15 * 60_000 else due
            }
        }.minOrNull()
        val alarms = context.getSystemService(AlarmManager::class.java)
        val alarm = PendingIntent.getBroadcast(
            context,
            JOB_ID,
            Intent(context, AgentTaskReceiver::class.java).setAction(AgentTaskReceiver.ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarms.cancel(alarm)
        if (earliest != null) {
            // 下限只用来防抖：到期时间还在未来就按原时刻排（本 ROM 的闹钟可能提前几十秒响，
            // 之前统一 +60s 下限会让每次提前触发都往后推一分钟）。
            val nowMs = System.currentTimeMillis()
            val triggerAt = if (earliest > nowMs) earliest else nowMs + 60_000
            // 精确闹钟优先：setAndAllowWhileIdle 在 Doze 下会被推迟到几小时后（实测 08:00 的任务 09:58 才跑）。
            // 清单已声明 USE_EXACT_ALARM / SCHEDULE_EXACT_ALARM，正常情况下 canScheduleExactAlarms 为 true；
            // 系统确实不给时退回不精确闹钟，并留一行 trace 便于排查。
            val exactOk = runCatching { alarms.canScheduleExactAlarms() }.getOrDefault(false)
            EtaTrace.log("task.refresh", "earliest=$earliest exact=$exactOk")
            // 关键：息屏/Doze 下 setExactAndAllowWhileIdle 仍会被推迟（实测 12:51:29 的闹钟 12:55:21 才响），
            // 只有 setAlarmClock 是系统承诺「准点、并唤醒设备」的通道（代价是状态栏出现闹钟图标）。
            val clockOk = exactOk && runCatching {
                val show = PendingIntent.getActivity(
                    context,
                    JOB_ID,
                    Intent(context, io.github.mangi.eta.ui.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), alarm)
            }.onFailure {
                AndroidAgentLogger.warn("Alarm clock scheduling failed: type=${it.javaClass.simpleName}")
            }.isSuccess
            if (!clockOk) {
                if (exactOk) {
                    runCatching {
                        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarm)
                    }.onFailure {
                        AndroidAgentLogger.warn("Exact alarm failed, falling back: type=${it.javaClass.simpleName}")
                        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarm)
                    }
                } else {
                    AndroidAgentLogger.warn("Exact alarm not permitted, using inexact (may be delayed)")
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarm)
                }
            }
            EtaTrace.log("task.refresh.arm", "clock=$clockOk triggerAt=$triggerAt")
            // 省电优先：不排「预唤醒」闹钟（实测被深睡策略扣住、白多一次唤醒），
            // 靠主闹钟 + 事件驱动补跑（opportunisticRun）即可。残留的预唤醒闹钟顺手清掉。
            val stalePrewake = PendingIntent.getBroadcast(
                context,
                JOB_ID + 1,
                Intent(context, AgentTaskReceiver::class.java)
                    .setAction(AgentTaskReceiver.ACTION_PREWAKE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarms.cancel(stalePrewake)
        }
        val queued = dao.nextQueued() != null
        EtaTrace.log("task.refresh.done", "queued=$queued")
        synchronized(jobLock) {
            if (!jobRunning.get()) {
                if (queued) requestJob(context)
                else if (earliest != null) requestJob(context, (earliest - now).coerceAtLeast(0))
                else context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
            }
        }
        if (tasks.any { JSONObject(it.triggerJson).optString("type") in monitoredEventTypes }) {
            runCatching {
                context.startForegroundService(
                    Intent(
                        context,
                        AgentTriggerMonitorService::class.java
                    )
                )
            }
                .onFailure { AndroidAgentLogger.warn("Trigger monitor requires a foreground user entry") }
        } else context.stopService(Intent(context, AgentTriggerMonitorService::class.java))
    }

    /** 「重试中」通知上的「立刻重试」：把等着的重试提前到现在，不改动原本的重试节奏。 */
    fun retryNow(context: Context) = requestJob(context, 0)

    /**
     * 到期即跑：用 expedited 作业，绕开 JobScheduler 的批处理窗口。
     *
     * 老实现不管「现在就要跑」还是「明天再跑」都用同一条 setMinimumLatency + setPersisted 作业，
     * 系统按批处理窗口排期，实测把 08:00 的任务拖到 09:58 才起来（延迟来自作业本身，不是闹钟）。
     * expedited 与 persisted 互斥，所以立即执行那条不带 persisted：开机由 BOOT_COMPLETED 重排即可。
     */
    fun requestJob(context: Context, delayMs: Long = 0) = synchronized(jobLock) {
        if (jobRunning.get()) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val delay = delayMs.coerceIn(0, TimeUnit.DAYS.toMillis(365))
        val immediate = delay <= 0
        val builder = JobInfo.Builder(JOB_ID, ComponentName(context, AgentTaskJobService::class.java))
        if (immediate) {
            builder.setExpedited(true)
        } else {
            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + 60_000)
                .setPersisted(true)
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        }
        var result = runCatching { scheduler.schedule(builder.build()) }.getOrDefault(JobScheduler.RESULT_FAILURE)
        if (result != JobScheduler.RESULT_SUCCESS && immediate) {
            // expedited 配额用完时退回普通作业，延迟 0 至少能立刻进队列。
            result = runCatching {
                scheduler.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(context, AgentTaskJobService::class.java))
                        .setMinimumLatency(0).setOverrideDeadline(60_000).build()
                )
            }.getOrDefault(JobScheduler.RESULT_FAILURE)
            AndroidAgentLogger.warn("Expedited job rejected, fell back to normal job")
        }
        if (result != JobScheduler.RESULT_SUCCESS) AndroidAgentLogger.warn("Automation job scheduling rejected")
        Unit
    }

    // ── defer 阶梯：以前无论哪个原因都用固定 60s 无限重排，静默等到几小时后；
    //    现在有上限、有留存计数、放弃时把原因写进运行记录并通知用户。
    private const val DEFER_PREFS = "eta_automation_defer"
    private val deferLadderMs = longArrayOf(30_000L, 120_000L, 300_000L, 900_000L, 1_800_000L)

    fun deferCount(context: Context, runId: String): Int =
        context.getSharedPreferences(DEFER_PREFS, Context.MODE_PRIVATE).getInt(runId, 0)

    /** 返回下一次重试延迟；null 表示阶梯用尽，调用方应判这次运行为失败。 */
    fun nextDeferDelay(context: Context, runId: String): Long? {
        val prefs = context.getSharedPreferences(DEFER_PREFS, Context.MODE_PRIVATE)
        val n = prefs.getInt(runId, 0)
        if (n >= deferLadderMs.size) return null
        prefs.edit().putInt(runId, n + 1).apply()
        return deferLadderMs[n]
    }

    fun clearDefer(context: Context, runId: String) {
        context.getSharedPreferences(DEFER_PREFS, Context.MODE_PRIVATE).edit().remove(runId).apply()
    }

    fun state(context: Context): Map<String, Boolean> {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val network = context.getSystemService(ConnectivityManager::class.java)
        return mapOf(
            "charging" to ((battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0),
            "unlocked" to !context.getSystemService(KeyguardManager::class.java).isDeviceLocked,
            "networkConnected" to (network.activeNetwork != null)
        )
    }

    fun enqueueDue(context: Context, now: Long = System.currentTimeMillis()) = runBlocking {
        val dao = EtaDatabase.get(context).agentTaskDao()
        val state = state(context)
        dao.tasks().filter { it.enabled && it.nextRunAt?.let { due -> due <= now } == true }
            .forEach { task ->
                val rule = JSONObject(task.triggerJson)
                if (AgentTaskRules.conditionsMatch(rule, state)) {
                    dao.enqueue(
                        task,
                        AgentTaskRunEntity(
                            UUID.randomUUID().toString(),
                            task.id,
                            "time:${task.nextRunAt}",
                            queuedAt = now
                        ),
                        AgentTaskRules.nextTime(rule, now, false)
                    )
                } else {
                    // 保留原定时点用于去重；稍后由普通调度再检查条件，不提前执行。
                    requestJob(context, 15 * 60_000)
                }
            }
    }

    fun enqueueManual(context: Context, task: AgentTaskEntity): Boolean = runBlocking {
        val run = AgentTaskRunEntity(
            UUID.randomUUID().toString(),
            task.id,
            "manual:${UUID.randomUUID()}",
            queuedAt = System.currentTimeMillis()
        )
        EtaDatabase.get(context).agentTaskDao().enqueue(task, run, task.nextRunAt, manual = true)
            .also { if (it) requestJob(context) }
    }

    fun publish(context: Context, type: String, key: String, data: JSONObject = JSONObject()) {
        if (type !in AgentTaskRules.eventTypes) return
        val appContext = context.applicationContext
        val copy = JSONObject(data.toString())
        eventWorker.execute {
            runCatching {
                runBlocking {
                    val dao = EtaDatabase.get(appContext).agentTaskDao()
                    val now = System.currentTimeMillis()
                    val state = state(appContext)
                    var queued = false
                    dao.tasks().filter { it.enabled }.forEach { task ->
                        val rule = JSONObject(task.triggerJson)
                        if (task.id == copy.optString("taskId")) return@forEach
                        // 防止全局任务完成事件形成自动循环；要求精确指定来源任务。
                        if (type in setOf(
                                "task_completed",
                                "task_failed"
                            ) && rule.optJSONObject("filters")?.optString("taskId").isNullOrBlank()
                        ) return@forEach
                        if (AgentTaskRules.matches(
                                rule,
                                type,
                                copy
                            ) && AgentTaskRules.conditionsMatch(rule, state)
                        ) {
                            val event = JSONObject().put("type", type)
                            for (field in listOf(
                                "packageName",
                                "taskId",
                                "transport"
                            )) if (copy.has(field)) event.put(
                                field,
                                copy.optString(field).take(200)
                            )
                            queued = dao.enqueue(
                                task, AgentTaskRunEntity(
                                    UUID.randomUUID().toString(),
                                    task.id,
                                    "$type:${key.take(200)}",
                                    eventJson = event.toString(),
                                    queuedAt = now
                                ), null
                            ) || queued
                        }
                    }
                    if (queued) requestJob(appContext)
                }
            }.onFailure { AndroidAgentLogger.warn("Trigger event could not be queued") }
        }
    }

    fun capabilities(context: Context): JSONObject {
        val triggers = JSONArray()
        (AgentTaskRules.timeTypes + AgentTaskRules.eventTypes).forEach { type ->
            val available = available(context, type)
            val scope = when (type) {
                "notification_posted", "notification_removed" -> "需要系统通知读取授权；不回放连接前通知，忽略 Eta 自身通知"
                "app_foreground" -> "需要 Eta 无障碍服务连接；按窗口事件触发"
                "bluetooth_connected", "bluetooth_disconnected" -> "需要蓝牙连接授权"
                "memory_updated", "task_completed", "task_failed" -> "Eta 本地事件；任务结果触发需要 filters.taskId 防止循环"
                in AgentTaskRules.timeTypes -> "精确闹钟 + JobScheduler；系统不授予精确闹钟权限时会退化并可能延迟"
                else -> "事件监控前台服务运行期间接收；系统结束服务时不补发"
            }
            triggers.put(
                JSONObject().put("type", type).put("available", available).put("scope", scope)
            )
        }
        listOf(
            "location_enter",
            "location_exit",
            "calendar_near",
            "file_changed",
            "shared_content",
            "vivo_memory_updated"
        ).forEach { type ->
            triggers.put(
                JSONObject().put("type", type).put("available", false)
                    .put("scope", "待适配，当前不能创建此类任务")
            )
        }
        return JSONObject().put("triggers", triggers)
            .put("monitorRunning", AgentTriggerMonitorService.isRunning)
            .put("conditions", JSONArray(listOf("charging", "unlocked", "networkConnected")))
            .put(
                "timeExamples", JSONArray(
                    listOf(
                        JSONObject().put("type", "once").put("at", "2026-10-10T09:00:00+08:00"),
                        JSONObject().put("type", "interval").put("seconds", 3600),
                        JSONObject().put("type", "daily").put("time", "09:00")
                            .put("timeZone", "Asia/Shanghai")
                    )
                )
            )
    }

    fun requireTriggerAvailable(context: Context, rule: JSONObject) {
        require(
            available(
                context,
                rule.getString("type")
            )
        ) { "触发来源未授权，请先 tasks_triggers；也可 enabled=false 保存" }
        if (rule.getString("type") in setOf(
                "task_completed",
                "task_failed"
            )
        ) require(
            !rule.optJSONObject("filters")?.optString("taskId").isNullOrBlank()
        ) { "任务结果触发必须指定 filters.taskId" }
    }

    private fun available(context: Context, type: String): Boolean = when (type) {
        "notification_posted", "notification_removed" -> AgentNotificationHistoryService.isEnabled(
            context
        )

        "app_foreground" -> AgentAccessibilityService.isAvailable()
        "bluetooth_connected", "bluetooth_disconnected" -> context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else -> type in AgentTaskRules.timeTypes || type in AgentTaskRules.eventTypes
    }

    private val monitoredEventTypes = AgentTaskRules.eventTypes - setOf(
        "notification_posted",
        "notification_removed",
        "app_foreground",
        "memory_updated",
        "task_completed",
        "task_failed",
        "device_boot",
    )
}
