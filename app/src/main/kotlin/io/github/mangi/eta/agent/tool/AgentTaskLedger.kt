package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.core.EtaTrace
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工作台账：把「这次运行做到哪一步、每步有什么回执」落到文件里，而不是留在上下文里。
 *
 * 三个问题一起解决：
 * 1. 长链条跑到第七八步就糊 —— 进度在文件里，随时能重读，不靠印象；
 * 2. 幻觉式完成 —— 每条记录必须带 evidence（命令回显 / 读回值 / trace 行），
 *    没有证据的条目在 review 时会显式列出；
 * 3. 重复动作 —— "发过第几次"写在台账里，重试前先查，避免双提交。
 *
 * 文件位置：files/agent/ledger/&lt;taskId&gt;.json（与 trace 同一私有目录，进程重启不丢）。
 */
internal object AgentLedger {

    /** 未确认态：动作已发出但没有回执。此状态只允许「再观察一次」，不允许原样重放。 */
    const val STATUS_UNCONFIRMED = "unconfirmed"
    const val STATUS_OK = "ok"
    const val STATUS_FAILED = "failed"

    private const val MAX_ENTRIES = 120
    private const val MAX_EVIDENCE_CHARS = 600

    private fun dir(context: Context) = java.io.File(context.filesDir, "agent/ledger").apply { mkdirs() }

    private fun file(context: Context, taskId: String) = java.io.File(dir(context), sanitize(taskId) + ".json")

    fun sanitize(taskId: String): String =
        taskId.trim().ifBlank { "default" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)

    /**
     * 追加一条记录。返回写入后的条目数。
     * evidence 为空时仍允许写入，但会标成 unconfirmed：这正是要暴露出来的那种情况。
     */
    fun record(
        context: Context,
        taskId: String,
        step: String,
        status: String,
        evidence: String,
        tool: String = "",
        dedupeKey: String = "",
    ): Int = synchronized(this) {
        val payload = readPayload(context, taskId)
        val entries = payload.optJSONArray("entries") ?: JSONArray()
        if (dedupeKey.isNotBlank()) {
            for (index in 0 until entries.length()) {
                if (entries.optJSONObject(index)?.optString("dedupe_key") == dedupeKey) {
                    return@synchronized entries.length()
                }
            }
        }
        entries.put(
            JSONObject()
                .put("step", step.take(200))
                .put("status", status.take(16))
                .put("evidence", evidence.take(MAX_EVIDENCE_CHARS))
                .put("tool", tool.take(64))
                .put("dedupe_key", dedupeKey.take(120))
                .put("at", System.currentTimeMillis()),
        )
        val trimmed = if (entries.length() > MAX_ENTRIES) {
            JSONArray().also { array ->
                for (index in entries.length() - MAX_ENTRIES until entries.length()) {
                    array.put(entries.getJSONObject(index))
                }
            }
        } else {
            entries
        }
        payload.put("task_id", sanitize(taskId))
        payload.put("entries", trimmed)
        runCatching { file(context, taskId).writeText(payload.toString(), Charsets.UTF_8) }
        EtaTrace.log("ledger", "record task=${sanitize(taskId)} step=${step.take(40)} status=$status")
        trimmed.length()
    }

    fun read(context: Context, taskId: String): JSONObject = readPayload(context, taskId)

    fun clear(context: Context, taskId: String): Boolean =
        runCatching { file(context, taskId).delete() }.getOrDefault(false)

    /**
     * 收尾自检：把没有证据的条目挑出来。
     * 模型看到这份清单，就必须去补取证或者把结论降级为「未确认」。
     */
    fun review(context: Context, taskId: String): JSONObject {
        val payload = readPayload(context, taskId)
        val entries = payload.optJSONArray("entries") ?: JSONArray()
        val missing = JSONArray()
        var ok = 0
        var failed = 0
        var unconfirmed = 0
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            when (entry.optString("status")) {
                STATUS_OK -> ok++
                STATUS_FAILED -> failed++
                else -> unconfirmed++
            }
            if (entry.optString("evidence").isBlank()) {
                missing.put(
                    JSONObject()
                        .put("step", entry.optString("step"))
                        .put("at", entry.optLong("at")),
                )
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("task_id", sanitize(taskId))
            .put("total", entries.length())
            .put("ok_count", ok)
            .put("failed_count", failed)
            .put("unconfirmed_count", unconfirmed)
            .put("missing_evidence", missing)
            .put(
                "hint",
                if (missing.length() > 0) {
                    "有 ${missing.length()} 条记录没有回执：这些只能在回答里写「未确认」，或者现在去补一次取证，不能直接声称完成。"
                } else {
                    "所有记录都带回执；收尾时按台账逐条陈述即可。"
                },
            )
    }

    private fun readPayload(context: Context, taskId: String): JSONObject {
        val text = runCatching { file(context, taskId).readText(Charsets.UTF_8) }.getOrNull()
        if (text.isNullOrBlank()) return JSONObject().put("entries", JSONArray())
        return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("entries", JSONArray()) }
    }
}

/** task_ledger 工具的实现：模型直接在 loop 里记 / 查 / 自检。 */
internal class AgentTaskLedgerTools(private val context: Context) {

    fun execute(toolName: String, args: JSONObject): String = when (toolName) {
        "task_ledger" -> when (args.optString("action")) {
            "record" -> record(args)
            "review" -> AgentLedger.review(context, taskId(args)).toString()
            "read" -> AgentLedger.read(context, taskId(args)).toString()
            "clear" -> JSONObject().put("ok", AgentLedger.clear(context, taskId(args))).toString()
            else -> JSONObject()
                .put("ok", false)
                .put("code", "BAD_ACTION")
                .put("message", "action 支持 record / review / read / clear")
                .toString()
        }
        else -> JSONObject().put("ok", false).put("code", "UNSUPPORTED").toString()
    }

    private fun record(args: JSONObject): String {
        val step = args.optString("step").trim()
        if (step.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("code", "STEP_REQUIRED")
                .put("message", "record 必须给 step")
                .toString()
        }
        val evidence = args.optString("evidence").trim()
        val status = when (args.optString("status").trim().lowercase()) {
            AgentLedger.STATUS_OK -> AgentLedger.STATUS_OK
            AgentLedger.STATUS_FAILED -> AgentLedger.STATUS_FAILED
            else -> AgentLedger.STATUS_UNCONFIRMED
        }.let { resolved ->
            // 没证据就不许记成 ok：这是台账存在的意义。
            if (resolved == AgentLedger.STATUS_OK && evidence.isBlank()) AgentLedger.STATUS_UNCONFIRMED else resolved
        }
        val count = AgentLedger.record(
            context = context,
            taskId = taskId(args),
            step = step,
            status = status,
            evidence = evidence,
            tool = args.optString("tool"),
            dedupeKey = args.optString("dedupe_key"),
        )
        return JSONObject()
            .put("ok", true)
            .put("status", status)
            .put("entries", count)
            .put("task_id", AgentLedger.sanitize(taskId(args)))
            .put(
                "message",
                if (status == AgentLedger.STATUS_UNCONFIRMED) {
                    "已记录，但标记为未确认（没有回执）。去补一次取证，或最终回答里明确写「未确认」。"
                } else {
                    "已记录。"
                },
            )
            .toString()
    }

    private fun taskId(args: JSONObject): String =
        args.optString("task_id").trim().ifBlank { DEFAULT_TASK_ID }

    companion object {
        val NAMES = setOf("task_ledger")
        const val DEFAULT_TASK_ID = "default"
    }
}
