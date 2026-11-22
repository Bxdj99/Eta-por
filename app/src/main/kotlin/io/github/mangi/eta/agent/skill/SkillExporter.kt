package io.github.mangi.eta.agent.skill

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Skill 导出：把选中的技能目录原样打成一个 ZIP，结构与导入侧对称——
 * 导入期望「包含 SKILL.md 的 Skill 包」，导出就把每个技能的整个目录放进去。
 *
 * 为什么不做成「一个技能一个 zip」：用户要的是自由挑选多个一起带走，
 * 单包更省事，导入侧也认（SkillPackageInstaller 会递归找 SKILL.md）。
 */
internal object SkillExporter {

    data class Result(val ok: Boolean, val fileCount: Int = 0, val error: String = "")

    /**
     * 把 [skillIds] 对应的技能目录写进 [target]。
     *
     * 目录布局：<root>/<skillId>/SKILL.md, <root>/<skillId>/references/...
     * 与导入时的查找逻辑一致，所以导出的包可以直接再导入。
     */
    fun export(
        context: Context,
        skillIds: Collection<String>,
        target: Uri,
    ): Result {
        if (skillIds.isEmpty()) return Result(false, error = "未选择任何技能")

        val root = SkillRuntime.skillsRoot(context)
        val sources = skillIds.mapNotNull { id ->
            val dir = File(root, id)
            if (dir.isDirectory && File(dir, "SKILL.md").isFile) dir else null
        }
        if (sources.isEmpty()) return Result(false, error = "选中的技能目录都不存在或不含 SKILL.md")

        var count = 0
        return try {
            val output = context.contentResolver.openOutputStream(target)
                ?: return Result(false, error = "无法写入目标文件")
            output.use { stream ->
                ZipOutputStream(stream.buffered()).use { zip ->
                    sources.forEach { dir ->
                        // 每个技能以自身 id 作为顶层目录写进去，保持多技能可区分。
                        dir.walkTopDown()
                            .filter { it.isFile }
                            .forEach { file ->
                                val relative = file.relativeTo(dir).invariantSeparatorsPath
                                zip.putNextEntry(ZipEntry("${dir.name}/$relative"))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                                count++
                            }
                    }
                }
            }
            Result(true, fileCount = count)
        } catch (error: Exception) {
            Result(false, error = error.message ?: "导出失败")
        }
    }

    /**
     * 用系统文档选择器挑保存位置时，给一个默认文件名。
     * 单个技能用它的 id，多个用数量概括，避免过长的名字。
     */
    fun suggestedFileName(skillIds: Collection<String>): String {
        val ids = skillIds.toList()
        return when (ids.size) {
            0 -> "eta-skills.zip"
            1 -> "${ids.first()}.zip"
            else -> "eta-skills-${ids.size}.zip"
        }
    }
}
