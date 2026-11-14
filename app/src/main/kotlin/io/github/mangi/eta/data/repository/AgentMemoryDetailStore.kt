package io.github.mangi.eta.data.repository

import android.content.Context
import java.io.File

/**
 * 记忆的细节区：MEMORY.md 只留「核心 + 索引 + 指针」，正文细节落到这里。
 *
 * 为什么要分家：核心记忆每次运行都会整段注入，越写越长会挤占上下文；
 * 而细节（路径、命令、键名、踩坑过程）平时用不上，只在问到时才需要。
 * 所以核心区放结论和指针，细节区按主题文件保存，[AgentMemoryDetailStore.list] 负责列目录，
 * 模型先看目录，再按需读一个文件——这就是「热区索引 + 冷区正文」的落地方式。
 *
 * 存放位置：`files/memory/details/<主题>.md`，与 MEMORY.md 同一私有目录。
 */
internal class AgentMemoryDetailStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "memory/details").apply { mkdirs() }

    data class Entry(val name: String, val bytes: Long, val updatedAt: Long, val title: String)

    fun list(): List<Entry> =
        (dir.listFiles { file -> file.isFile && file.name.endsWith(".md") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { file ->
                Entry(
                    name = file.nameWithoutExtension,
                    bytes = file.length(),
                    updatedAt = file.lastModified(),
                    title = runCatching {
                        file.useLines { lines -> lines.firstOrNull { it.isNotBlank() } ?: "" }
                    }.getOrDefault(""),
                )
            }

    fun read(name: String, maxChars: Int = DEFAULT_READ_CHARS): Pair<String, Boolean> {
        val file = resolve(name) ?: return "" to false
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
        val limit = maxChars.coerceIn(1_000, MAX_READ_CHARS)
        return if (text.length <= limit) text to false else text.take(limit) to true
    }

    /** 整篇覆盖写；[title] 会作为第一行标题写进去，方便 [list] 直接给出可读名字。 */
    fun write(name: String, title: String, content: String): Int {
        val file = File(dir, sanitize(name) + ".md")
        val body = buildString {
            if (title.isNotBlank()) appendLine("# " + title.trim())
            append(content.trimEnd())
            appendLine()
        }
        file.writeText(body, Charsets.UTF_8)
        return body.length
    }

    fun delete(name: String): Boolean = (resolve(name)?.delete() ?: false)

    fun exists(name: String): Boolean = resolve(name)?.exists() == true

    private fun resolve(name: String): File? {
        val safe = sanitize(name)
        if (safe.isBlank()) return null
        val file = File(dir, safe + ".md")
        // 只允许落在 details 目录内，避免 ../ 之类跳出记忆区。
        return if (file.canonicalPath.startsWith(dir.canonicalPath)) file else null
    }

    private fun sanitize(name: String): String =
        name.trim().removeSuffix(".md").replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5._-]"), "_").take(64)

    companion object {
        const val DEFAULT_READ_CHARS = 8_000
        const val MAX_READ_CHARS = 32_000
    }
}
