package io.github.mangi.eta.agent.tool

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 台账行为契约：
 * 1. 没有 evidence 的 ok 必须被降级为 unconfirmed（防「口头完成」）；
 * 2. 同一 dedupe_key 不重复追加（防重试灌满、防双提交后重复记账）；
 * 3. review 必须把缺回执的条目单独列出来。
 */
@RunWith(RobolectricTestRunner::class)
class AgentTaskLedgerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val tools by lazy { AgentTaskLedgerTools(context) }

    @After
    fun cleanup() {
        AgentLedger.clear(context, "unit-test")
    }

    @Test
    fun `ok without evidence is downgraded to unconfirmed`() {
        val result = JSONObject(
            tools.execute(
                "task_ledger",
                JSONObject()
                    .put("action", "record")
                    .put("task_id", "unit-test")
                    .put("step", "声称装包完成")
                    .put("status", "ok"),
            ),
        )
        assertEquals(true, result.optBoolean("ok"))
        assertEquals(AgentLedger.STATUS_UNCONFIRMED, result.getString("status"))
    }

    @Test
    fun `ok with evidence stays ok`() {
        val result = JSONObject(
            tools.execute(
                "task_ledger",
                JSONObject()
                    .put("action", "record")
                    .put("task_id", "unit-test")
                    .put("step", "读回版本号")
                    .put("status", "ok")
                    .put("evidence", "versionName=3.4.1-por48"),
            ),
        )
        assertEquals(AgentLedger.STATUS_OK, result.getString("status"))
        assertEquals(1, result.getInt("entries"))
    }

    @Test
    fun `same dedupe key is not appended twice`() {
        repeat(3) {
            tools.execute(
                "task_ledger",
                JSONObject()
                    .put("action", "record")
                    .put("task_id", "unit-test")
                    .put("step", "发送消息")
                    .put("status", "ok")
                    .put("evidence", "after: 会话最后一条为我方")
                    .put("dedupe_key", "send-msg-1"),
            )
        }
        val payload = JSONObject(tools.execute("task_ledger", JSONObject().put("action", "read").put("task_id", "unit-test")))
        assertEquals(1, payload.getJSONArray("entries").length())
    }

    @Test
    fun `review lists entries without evidence`() {
        tools.execute(
            "task_ledger",
            JSONObject().put("action", "record").put("task_id", "unit-test")
                .put("step", "有回执的步骤").put("status", "ok").put("evidence", "Success"),
        )
        tools.execute(
            "task_ledger",
            JSONObject().put("action", "record").put("task_id", "unit-test")
                .put("step", "没回执的步骤").put("status", "ok"),
        )
        val review = JSONObject(
            tools.execute("task_ledger", JSONObject().put("action", "review").put("task_id", "unit-test")),
        )
        assertEquals(2, review.getInt("total"))
        assertEquals(1, review.getInt("ok_count"))
        assertEquals(1, review.getInt("unconfirmed_count"))
        assertEquals(1, review.getJSONArray("missing_evidence").length())
        assertTrue(review.getString("hint").contains("没有回执"))
    }
}
