package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.core.EtaTrace
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
    const val STATUS_UNCONFIRMED = LedgerCore.STATUS_UNCONFIRMED
    const val STATUS_OK = LedgerCore.STATUS_OK
    const val STATUS_FAILED = LedgerCore.STATUS_FAILED

    private fun dir(context: Context) = java.io.File(context.filesDir, "agent/ledger").apply { mkdirs() }

    private fun file(context: Context, taskId: String) = LedgerCore.fileFor(dir(context), taskId)

    fun sanitize(taskId: String): String = LedgerCore.sanitize(taskId)

    /**
     * 追加一条记录。返回写入后的条目数。
     * evidence 为空时会落成 unconfirmed：这正是要暴露出来的那种情况。
     */
    fun record(
        context: Context,
        taskId: String,
        step: String,
        status: String,
        evidence: String,
        tool: String = "",
        dedupeKey: String = "",
        claimKey: String = "",
    ): Int = synchronized(this) {
        val count = LedgerCore.record(file(context, taskId), taskId, step, status, evidence, tool, dedupeKey, claimKey)
        EtaTrace.log(
            "ledger",
            "record task=${sanitize(taskId)} step=${step.take(40)} status=${LedgerCore.normalizeStatus(status, evidence)}",
        )
        count
    }

    /** 动手前登记一次外部副作用操作；返回 false 表示这个 claim_key 已经登记过（别再发一次）。 */
    fun claim(
        context: Context,
        taskId: String,
        claimKey: String,
        step: String,
        tool: String = "",
    ): Pair<Boolean, Int> {
        val result = synchronized(this) {
            LedgerCore.claim(file(context, taskId), taskId, claimKey, step, tool)
        }
        EtaTrace.log("ledger", "claim task=${sanitize(taskId)} key=${claimKey.take(40)} accepted=${result.first}")
        return result
    }

    fun read(context: Context, taskId: String): JSONObject = LedgerCore.readPayload(file(context, taskId))

    fun clear(context: Context, taskId: String): Boolean =
        runCatching { file(context, taskId).delete() }.getOrDefault(false)

    /** 收尾自检：把没有证据的条目挑出来，逼模型降级或补取证。 */
    fun review(context: Context, taskId: String): JSONObject = LedgerCore.review(file(context, taskId), taskId)
}

/** task_ledger 工具的实现：模型直接在 loop 里记 / 查 / 自检。 */
internal class AgentTaskLedgerTools(private val context: Context) {

    fun execute(toolName: String, args: JSONObject): String = when (toolName) {
        "task_ledger" -> when (args.optString("action")) {
            "record" -> record(args)
            "claim" -> claim(args)
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
        // 没证据就不许记成 ok：这是台账存在的意义。
        val status = LedgerCore.normalizeStatus(args.optString("status"), evidence)
        val claimKey = args.optString("claim_key").trim()
        val count = AgentLedger.record(
            context = context,
            taskId = taskId(args),
            step = step,
            status = status,
            evidence = evidence,
            tool = args.optString("tool"),
            dedupeKey = args.optString("dedupe_key"),
            claimKey = claimKey,
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

    /**
     * claim：动手前先登记。同一 claim_key 第二次进来直接返回 ALREADY_CLAIMED，
     * 模型据此转去核实结果，而不是把同一个副作用再做一遍。
     */
    private fun claim(args: JSONObject): String {
        val claimKey = args.optString("claim_key").trim()
        if (claimKey.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("code", "CLAIM_KEY_REQUIRED")
                .put("message", "claim 必须给 claim_key")
                .toString()
        }
        val step = args.optString("step").trim().ifBlank { claimKey }
        val (accepted, count) = AgentLedger.claim(
            context = context,
            taskId = taskId(args),
            claimKey = claimKey,
            step = step,
            tool = args.optString("tool"),
        )
        return if (accepted) {
            JSONObject()
                .put("ok", true)
                .put("claimed", true)
                .put("entries", count)
                .put("message", "已登记为进行中（unconfirmed）。做完用 confirm 带这个 claim_key 补取证。")
                .toString()
        } else {
            JSONObject()
                .put("ok", false)
                .put("code", "ALREADY_CLAIMED")
                .put("message", "这个动作已经登记过，不要再执行第二遍；先核实结果，要补证据用 action=confirm。")
                .toString()
        }
    }

    private fun taskId(args: JSONObject): String =
        args.optString("task_id").trim().ifBlank { DEFAULT_TASK_ID }

    companion object {
        val NAMES = setOf("task_ledger")
        const val DEFAULT_TASK_ID = "default"
    }
}
