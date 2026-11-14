package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject

/**
 * 台账内核：不依赖 Android，纯文件 + JSON。
 *
 * 拆出来的原因：这台机器离网，Robolectric / room-testing 拉不下来，跑不了本应用的单元测试。
 * 把逻辑收进纯 JVM 层以后，可以在 Linux 里用 kotlinc 直接编译 + 断言，
 * 用真实的 JAVA_HOME 跑一遍「没回执不许记 ok」「同 key 不重复」「review 列出缺证据」三条契约。
 *
 * 文件布局与 [AgentLedger] 完全一致，二者共用同一份实现。
 */
internal object LedgerCore {

    const val STATUS_OK = "ok"
    const val STATUS_FAILED = "failed"
    const val STATUS_UNCONFIRMED = "unconfirmed"

    private const val MAX_ENTRIES = 120
    private const val MAX_EVIDENCE_CHARS = 600

    fun sanitize(taskId: String): String =
        taskId.trim().ifBlank { "default" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)

    fun fileFor(dir: java.io.File, taskId: String) = java.io.File(dir, sanitize(taskId) + ".json")

    /** 没证据的 ok 一律降级：这是「不准口头完成」的硬闸门。 */
    fun normalizeStatus(status: String, evidence: String): String {
        val resolved = when (status.trim().lowercase()) {
            STATUS_OK -> STATUS_OK
            STATUS_FAILED -> STATUS_FAILED
            else -> STATUS_UNCONFIRMED
        }
        return if (resolved == STATUS_OK && evidence.isBlank()) STATUS_UNCONFIRMED else resolved
    }

    fun readPayload(file: java.io.File): JSONObject {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        if (text.isNullOrBlank()) return JSONObject().put("entries", JSONArray())
        return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("entries", JSONArray()) }
    }

    fun record(
        file: java.io.File,
        taskId: String,
        step: String,
        status: String,
        evidence: String,
        tool: String = "",
        dedupeKey: String = "",
    ): Int {
        val payload = readPayload(file)
        val entries = payload.optJSONArray("entries") ?: JSONArray()
        if (dedupeKey.isNotBlank()) {
            for (index in 0 until entries.length()) {
                if (entries.optJSONObject(index)?.optString("dedupe_key") == dedupeKey) {
                    return entries.length()
                }
            }
        }
        entries.put(
            JSONObject()
                .put("step", step.take(200))
                .put("status", normalizeStatus(status, evidence))
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
        file.parentFile?.mkdirs()
        file.writeText(payload.toString(), Charsets.UTF_8)
        return trimmed.length()
    }

    fun review(file: java.io.File, taskId: String): JSONObject {
        val payload = readPayload(file)
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
                missing.put(JSONObject().put("step", entry.optString("step")).put("at", entry.optLong("at")))
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
}
