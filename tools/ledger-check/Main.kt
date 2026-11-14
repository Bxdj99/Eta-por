import io.github.mangi.eta.agent.tool.LedgerCore
import java.io.File

var failures = 0
fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("PASS  $name") else { failures++; println("FAIL  $name  $detail") }
}

fun main() {
    val dir = File("/tmp/ledgertest/out").apply { deleteRecursively(); mkdirs() }
    val f = LedgerCore.fileFor(dir, "unit-test")

    // 1. 没回执的 ok 必须降级
    LedgerCore.record(f, "unit-test", "声称装包完成", "ok", "")
    val t1 = LedgerCore.readPayload(f).getJSONArray("entries").getJSONObject(0)
    check("ok 无回执降级为 unconfirmed", t1.getString("status") == "unconfirmed", t1.getString("status"))

    // 2. 有回执的 ok 保留
    LedgerCore.record(f, "unit-test", "读回版本号", "ok", "versionName=3.4.1-por49")
    val t2 = LedgerCore.readPayload(f).getJSONArray("entries").getJSONObject(1)
    check("ok 带回执保持 ok", t2.getString("status") == "ok", t2.getString("status"))

    // 3. 同 dedupe_key 不重复追加
    repeat(3) { LedgerCore.record(f, "unit-test", "发送消息", "ok", "after: 最后一条是我方", dedupeKey = "send-1") }
    val n3 = LedgerCore.readPayload(f).getJSONArray("entries").length()
    check("同 dedupe_key 只记一次", n3 == 3, "entries=$n3")

    // 4. review 列出缺回执
    val review = LedgerCore.review(f, "unit-test")
    check("review.total==3", review.getInt("total") == 3, review.toString())
    check("review.ok_count==2", review.getInt("ok_count") == 2, review.getString("ok_count"))
    check("review.unconfirmed_count==1", review.getInt("unconfirmed_count") == 1, review.getString("unconfirmed_count"))
    check("review.missing_evidence 1 条", review.getJSONArray("missing_evidence").length() == 1)
    check("review.hint 提到没有回执", review.getString("hint").contains("没有回执"), review.getString("hint"))

    // 5. 台账落盘可重读
    val reread = LedgerCore.readPayload(f)
    check("文件已落盘可重读", reread.getJSONArray("entries").length() == 3, f.absolutePath)

    // 6. 条目上限
    for (i in 0 until 150) LedgerCore.record(f, "unit-test", "步骤$i", "ok", "echo ok")
    check("条目上限 120", LedgerCore.readPayload(f).getJSONArray("entries").length() == 120)

    // 7. taskId 规范化
    val evil = LedgerCore.fileFor(dir, "../../escape")
    check("taskId 不跳出目录", evil.canonicalPath.startsWith(dir.canonicalPath), evil.canonicalPath)

    // ── claim / 双取证 ──
    val g = LedgerCore.fileFor(dir, "claim-test")
    val first = LedgerCore.claim(g, "claim-test", "send-msg-42", "发送第 1 条消息给张三")
    check("claim 首次接受", first.first, first.toString())
    val again = LedgerCore.claim(g, "claim-test", "send-msg-42", "发送第 1 条消息给张三")
    check("claim 第二次被拒绝", !again.first, again.toString())
    check("claim 被拒后条目不增加", again.second == 1, "count=${again.second}")
    check("claimed() 能查到", LedgerCore.claimed(g, "send-msg-42"))

    val c1 = LedgerCore.readPayload(g).getJSONArray("entries").getJSONObject(0)
    check("claim 落成 unconfirmed", c1.getString("status") == "unconfirmed", c1.getString("status"))
    check("claim 记下 claim_key", c1.optString("claim_key") == "claim:send-msg-42", c1.optString("claim_key"))

    // confirm 一次：数值条目仍提示只有单份取证
    LedgerCore.record(g, "claim-test", "发送第 1 条消息给张三", "ok", "会话最后一条为我方 msgId=88", claimKey = "send-msg-42")
    val r1 = LedgerCore.review(g, "claim-test")
    check("单份取证时 single_evidence_numeric 命中", r1.getJSONArray("single_evidence_numeric").length() == 1, r1.toString())
    check("单份取证时 hint 要求补第二份", r1.getString("hint").contains("独立取证"), r1.getString("hint"))

    // 第二份独立取证（不同 claim_key）
    LedgerCore.record(g, "claim-test", "发送第 1 条消息给张三", "ok", "短信发出记录 count=1", claimKey = "send-msg-42-verify")
    val r2 = LedgerCore.review(g, "claim-test")
    check("两份取证后不再提示单份", r2.getJSONArray("single_evidence_numeric").length() == 0, r2.toString())

    println(if (failures == 0) "ALL PASS" else "FAILURES=$failures")
    if (failures != 0) kotlin.system.exitProcess(1)
}
