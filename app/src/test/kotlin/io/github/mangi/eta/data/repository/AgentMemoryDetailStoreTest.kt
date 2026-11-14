package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 记忆细节区契约：写入可取回、目录能列出、名字被规范化、不能跳出 details 目录。 */
@RunWith(RobolectricTestRunner::class)
class AgentMemoryDetailStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `write then read round trip`() {
        val store = AgentMemoryDetailStore(context)
        val bytes = store.write("eta-灵屏", "灵屏排障笔记", "第一行\n第二行")
        assertTrue(bytes > 0)
        val (text, truncated) = store.read("eta-灵屏")
        assertTrue(text.contains("灵屏排障笔记"))
        assertTrue(text.contains("第二行"))
        assertFalse(truncated)
        store.delete("eta-灵屏")
    }

    @Test
    fun `list exposes title and size`() {
        val store = AgentMemoryDetailStore(context)
        store.write("unit-test-detail", "单元测试主题", "正文")
        val entry = store.list().firstOrNull { it.name == "unit-test-detail" }
        assertEquals("单元测试主题", entry?.title?.removePrefix("# "))
        store.delete("unit-test-detail")
    }

    @Test
    fun `path traversal cannot escape details directory`() {
        val store = AgentMemoryDetailStore(context)
        store.write("../../evil", "越界尝试", "x")
        val (text, _) = store.read("../../evil")
        assertTrue(text.contains("越界尝试"))
        val detailDir = java.io.File(context.filesDir, "memory/details")
        val escaped = java.io.File(context.filesDir, "evil.md")
        assertFalse("写入不得跳出 details 目录", escaped.exists())
        assertTrue(detailDir.listFiles()?.any { it.name.contains("evil") } == true)
    }
}
