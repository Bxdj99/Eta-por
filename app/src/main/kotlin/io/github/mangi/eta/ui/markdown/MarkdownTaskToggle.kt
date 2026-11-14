package io.github.mangi.eta.ui.markdown

/**
 * 任务列表勾选回写的入口桥。
 *
 * 消息气泡在好几条渲染路径里，逐层透传要把参数加到
 * ChatMessageItem → AgentChatBody → Shell → Root 四层；这里用一个小桥：
 * AgentAppState 在初始化时挂上处理器，ChatMessageItem 直接取。
 * 处理器为空（比如预览/导出场景）时勾选框保持只读。
 */
internal object MarkdownTaskToggleBridge {
    @Volatile
    var handler: ((messageId: String, sourceOffset: Int) -> Unit)? = null
}

private val taskMarker = Regex("\\[[ xX]\\]")

/**
 * 把 markdown 源码里某个偏移所在行的 `- [ ]` / `- [x]` 翻面。
 *
 * 只认"该行第一个任务标记"：列表项偏移由解析层给出，行内不会出现多个勾选框；
 * 找不到标记就返回 null，由调用方当没点过处理（不猜位置）。
 */
internal fun toggleMarkdownTaskAt(source: String, offset: Int): String? {
    if (source.isEmpty()) return null
    val safe = offset.coerceIn(0, source.length)
    val previousBreak = source.lastIndexOf('\n', (safe - 1).coerceAtLeast(0))
    val lineStart = if (previousBreak < 0) 0 else previousBreak + 1
    val nextBreak = source.indexOf('\n', safe).let { if (it < 0) source.length else it }
    val lineEnd = minOf(nextBreak, source.length)
    if (lineStart >= lineEnd) return null
    val line = source.substring(lineStart, lineEnd)
    val marker = taskMarker.find(line) ?: return null
    val checked = line[marker.range.first + 1].equals('x', ignoreCase = true)
    val replacement = if (checked) "[ ]" else "[x]"
    return source.replaceRange(
        lineStart + marker.range.first,
        lineStart + marker.range.last + 1,
        replacement,
    )
}
