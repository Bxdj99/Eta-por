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

    /** 没有数字但仍然是量化结论的词：这类也要求两份独立取证。 */
    private val QUANT_WORDS = listOf("版本", "数量", "条数", "大小", "耗时", "成功率", "余额", "端口", "包名版本")
    private const val MAX_EVIDENCE_CHARS = 600

    /** 同一个 claim_key 下有几条独立取证（不同 claim_key 的 ok 记录视为独立来源）。 */
    private fun evidenceSources(entries: JSONArray, entry: JSONObject): Int {
        // 同一件事的多份取证允许带 -verify / -2 这类后缀，归组时统一去掉。
        val key = transactionOf(entry.optString("claim_key"))
        var count = 0
        for (index in 0 until entries.length()) {
            val other = entries.optJSONObject(index) ?: continue
            if (other.optString("status") != STATUS_OK) continue
            if (other.optString("evidence").isBlank()) continue
            val otherKey = transactionOf(other.optString("claim_key"))
            when {
                key.isNotBlank() && otherKey == key -> count++
                key.isBlank() && other.optString("step") == entry.optString("step") -> count++
            }
        }
        return count
    }

    /** 事务标识：去掉 claim: 前缀与 -verify / -2 之类的补充取证后缀。 */
    private fun transactionOf(claimKey: String): String =
        claimKey.removePrefix("claim:")
            .replace(Regex("[-_](verify|verified|2|check|confirm|second)$"), "")
            .trim()

    /**
     * 判定是否为可量化的结论：版本号、数量、大小、页码、条数这类表述。
     * 只在「确实出现数字」或「明确的量化词」时才算——否则每一条都要两份取证，机制就变成了负担。
     */
    private fun looksNumeric(entry: JSONObject): Boolean {
        val text = entry.optString("step") + " " + entry.optString("evidence")
        if (Regex("[0-9]").containsMatchIn(text)) return true
        return QUANT_WORDS.any { text.contains(it) }
    }

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
        claimKey: String = "",
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
                .put("claim_key", claimKey.take(120))
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

    /** 当前条目数（给 claim 判定用）。 */
    fun count(file: java.io.File): Int =
        readPayload(file).optJSONArray("entries")?.length() ?: 0

    /** 某个 claim_key 是否已经被登记过。 */
    fun claimed(file: java.io.File, claimKey: String): Boolean {
        if (claimKey.isBlank()) return false
        val target = transactionOf(claimKey)
        val entries = readPayload(file).optJSONArray("entries") ?: return false
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val stored = entry.optString("claim_key")
            if (stored.isBlank()) continue
            val storedTransaction = transactionOf(stored)
            // 只算「登记」这一步：真正的 confirm 条目（claim_key 里带 -verify）不算重复登记。
            val isRegistration = stored == "claim:$target" || stored == target
            if (isRegistration && storedTransaction == target) return true
        }
        return false
    }

    /**
     * 一次外部副作用操作只能被登记一次。
     *
     * 这是「别做第二遍」的硬闸门：同一个 claim_key 第二次进来直接报 ALREADY_CLAIMED，
     * 模型看到回执就知道该去核实结果，而不是再发一次。
     */
    fun claim(
        file: java.io.File,
        taskId: String,
        claimKey: String,
        step: String,
        tool: String = "",
    ): Pair<Boolean, Int> {
        if (claimed(file, claimKey)) return false to count(file)
        val entries = record(
            file = file,
            taskId = taskId,
            step = step,
            status = STATUS_UNCONFIRMED,
            evidence = "",
            tool = tool,
            dedupeKey = "claim:$claimKey",
            // 用独立标记区分「已登记」与「已有回执」，避免 claim 把后续 confirm 的去重挡住。
            claimKey = "claim:$claimKey",
        )
        return true to entries
    }

    fun review(file: java.io.File, taskId: String): JSONObject {
        val payload = readPayload(file)
        val entries = payload.optJSONArray("entries") ?: JSONArray()
        val missing = JSONArray()
        val single = JSONArray()
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
            // 数值类验收：只收到一份独立取证就不算过关。
            // 判定顺序不能靠 && 短路把 debug 断掉，这里先算清楚再判断。
            val sources = evidenceSources(entries, entry)
            val numeric = looksNumeric(entry)
            val hasClaim = entry.optString("claim_key").isNotBlank()
            if (entry.optString("status") == STATUS_OK && hasClaim && numeric && sources < 2) {
                single.put(JSONObject().put("step", entry.optString("step")).put("evidence", entry.optString("evidence")))
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
            .put("single_evidence_numeric", single)
            .put(
                "hint",
                buildString {
                    if (missing.length() > 0) {
                        append("有 ${missing.length()} 条记录没有回执：这些只能在回答里写「未确认」，或者现在去补一次取证，不能直接声称完成。")
                    }
                    if (single.length() > 0) {
                        if (isNotEmpty()) append(" ")
                        append("另有 ${single.length()} 条数值结论只有一份取证：再补一次独立取证（换个命令或换个字段读回）才算通过。")
                    }
                    if (isEmpty()) append("所有记录都带回执；收尾时按台账逐条陈述即可。")
                },
            )
    }
}
