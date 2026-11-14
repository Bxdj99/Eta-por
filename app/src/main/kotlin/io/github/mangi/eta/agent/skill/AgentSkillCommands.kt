package io.github.mangi.eta.agent.skill

import android.content.Context
import io.github.mangi.eta.agent.model.AgentSkillCommandPromptCodec
import java.io.File

/**
 * 斜杠命令：`/技能名 这次要做什么`。
 *
 * 只在"整段输入以 `/别名` 开头且别名能命中已启用技能"时才展开，其余情况原样返回，
 * 避免用户正常打字里的 `/` 被吃掉。展开后正文内联进消息（不依赖 skills_read），
 * 展示层再由 [AgentSkillCommandPromptCodec.strip] 把块剥掉。
 */
internal object AgentSkillCommands {

    data class Suggestion(
        val alias: String,
        val name: String,
        val description: String,
    )

    private val pattern = Regex("^/([^\\s/]{1,64})\\s*([\\s\\S]*)$")

    fun all(context: Context): List<Suggestion> = runCatching {
        SkillRuntime.createIndexService(context).listInstalledSkills().map { entry ->
            Suggestion(
                alias = aliasOf(entry),
                name = entry.name.ifBlank { entry.id },
                description = entry.description.lineSequence().firstOrNull().orEmpty().trim(),
            )
        }
    }.getOrDefault(emptyList())

    fun suggest(context: Context, query: String): List<Suggestion> {
        val keyword = query.trim().lowercase()
        val items = all(context)
        if (keyword.isEmpty()) return items
        return items.filter { item ->
            item.alias.lowercase().contains(keyword) || item.name.lowercase().contains(keyword)
        }
    }

    /** 输入框里紧跟 `/` 的那段字；不是斜杠开头或已经带换行时返回 null。 */
    fun queryOf(text: String): String? {
        if (!text.startsWith("/")) return null
        if (text.contains('\n')) return null
        return text.drop(1).takeIf { !it.contains('/') && !it.contains(' ') }
    }

    /**
     * 把 `/别名 正文` 展开成带技能块的用户消息；命中不了技能就返回 null（调用方保持原文）。
     */
    fun expand(context: Context, text: String): String? {
        val match = pattern.find(text.trim()) ?: return null
        val alias = match.groupValues[1]
        val service = runCatching { SkillRuntime.createIndexService(context) }.getOrNull() ?: return null
        val entry = runCatching { service.findInstalledSkill(alias) }.getOrNull() ?: return null
        val request = match.groupValues[2]
        val body = runCatching { File(entry.skillFilePath).readText() }.getOrDefault("")
        val truncated = body.length > AgentSkillCommandPromptCodec.BodyLimit
        return AgentSkillCommandPromptCodec.format(
            skillId = entry.id,
            skillName = entry.name.ifBlank { entry.id },
            skillPath = entry.skillFilePath,
            body = if (truncated) body.take(AgentSkillCommandPromptCodec.BodyLimit) else body,
            truncated = truncated,
            request = request,
        )
    }

    private fun aliasOf(entry: SkillIndexEntry): String = entry.id.trim().ifBlank { entry.name.trim() }
}
