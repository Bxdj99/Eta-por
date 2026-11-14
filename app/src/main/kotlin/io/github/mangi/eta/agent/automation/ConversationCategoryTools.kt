package io.github.mangi.eta.agent.automation

import android.content.Context
import io.github.mangi.eta.data.db.ConversationCategoryEntity
import io.github.mangi.eta.data.db.ConversationCategoryLinkEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话分类的读写入口（移植自 fork 的「会话分类」能力）。
 *
 * 为什么做成工具而不是只做界面：分类的交互在 COUI 侧栏里要一层层点（长按→移动到分类→选/新建），
 * 先用工具把「能建、能归类、能删」打通，模型和用户都能立刻用；点选界面随后补。
 *
 * action:
 *  - list：列出全部分类及其会话数
 *  - create：{name} 新建分类
 *  - rename：{categoryId,name}
 *  - delete：{categoryId} 只解散归属，不删会话
 *  - assign：{conversationId,categoryId}，categoryId 传空串表示移出分类
 */
internal class ConversationCategoryTools(context: Context) {
    private val context = context.applicationContext
    private val dao get() = EtaDatabase.get(context).conversationCategoryDao()

    fun execute(name: String, args: JSONObject): String = runBlocking {
        try {
            when (args.optString("action", "list")) {
                "list" -> {
                    val links = dao.links()
                    val counts = links.groupingBy { it.categoryId }.eachCount()
                    val arr = JSONArray()
                    dao.categories().forEach { category ->
                        arr.put(
                            JSONObject()
                                .put("categoryId", category.id)
                                .put("name", category.name)
                                .put("sortOrder", category.sortOrder)
                                .put("conversations", counts[category.id] ?: 0)
                        )
                    }
                    JSONObject().put("ok", true).put("categories", arr).toString()
                }

                "create" -> {
                    val name = args.optString("name").trim()
                    require(name.isNotEmpty()) { "name 不能为空" }
                    require(dao.categories().size < 50) { "最多 50 个分类" }
                    val existing = dao.categories()
                    val entity = ConversationCategoryEntity(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 1,
                        createdAt = System.currentTimeMillis(),
                    )
                    dao.upsert(entity)
                    JSONObject().put("ok", true).put("categoryId", entity.id).put("name", name).toString()
                }

                "rename" -> {
                    val id = args.optString("categoryId")
                    val name = args.optString("name").trim()
                    require(id.isNotEmpty() && name.isNotEmpty()) { "categoryId 与 name 都必填" }
                    val old = dao.categories().firstOrNull { it.id == id }
                        ?: return@runBlocking JSONObject().put("ok", false).put("error", "分类不存在").toString()
                    dao.upsert(old.copy(name = name))
                    JSONObject().put("ok", true).toString()
                }

                "delete" -> {
                    val id = args.optString("categoryId")
                    require(id.isNotEmpty()) { "categoryId 必填" }
                    dao.unlinkCategory(id)
                    dao.delete(id)
                    JSONObject().put("ok", true).toString()
                }

                "assign" -> {
                    val conversationId = args.optString("conversationId")
                    require(conversationId.isNotEmpty()) { "conversationId 必填" }
                    val categoryId = args.optString("categoryId")
                    if (categoryId.isBlank()) {
                        dao.unlink(conversationId)
                        JSONObject().put("ok", true).put("categoryId", "").toString()
                    } else {
                        require(dao.categories().any { it.id == categoryId }) { "分类不存在" }
                        dao.link(
                            ConversationCategoryLinkEntity(
                                conversationId = conversationId,
                                categoryId = categoryId,
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        JSONObject().put("ok", true).put("categoryId", categoryId).toString()
                    }
                }

                else -> JSONObject().put("ok", false).put("error", "未知 action").toString()
            }
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.message ?: t.javaClass.simpleName).toString()
        }
    }

    companion object {
        val names = setOf("conversation_categories")
    }
}
