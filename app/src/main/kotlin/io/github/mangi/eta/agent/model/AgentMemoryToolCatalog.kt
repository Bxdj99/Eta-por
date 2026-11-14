package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.repository.AgentMemoryStore
import org.json.JSONArray
import org.json.JSONObject

/** 声明持久记忆的有界读取与原子局部更新工具。 */
internal object AgentMemoryToolCatalog {
    fun appendTo(tools: JSONArray, writable: Boolean = true) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "memory_get",
                    description = "Read persistent cross-conversation memory from MEMORY.md. Core memory and the heading index are already provided at run start, so call this only to inspect details, answer what is remembered, or refresh after a conflict. Use query for just-in-time retrieval instead of reading the whole file.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "Optional case-insensitive text to search for in the full memory file."),
                                )
                                .put(
                                    "start_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "1-based first line for paged reading when query is omitted; default 1."),
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentMemoryStore.MIN_READ_CHARS)
                                        .put("maximum", AgentMemoryStore.MAX_READ_CHARS)
                                        .put("description", "Maximum returned characters; default 12000, maximum 32000."),
                                ),
                        ),
                ),
            )
        // 细节区工具：核心记忆放结论与指针，正文细节落到 files/memory/details/<主题>.md。
        tools.put(
            AgentToolSchema.function(
                name = "memory_detail",
                description = "记忆的细节区（冷区）。核心记忆 MEMORY.md 只保留结论、关键参数和指针，正文细节写在这里，按主题分文件。" +
                    "list：列出已有主题（名字、大小、更新时间、首行标题）；read：读一个主题的正文；write：整篇覆盖写；delete：删除。" +
                    "写入时机：内容超过三五行、属于踩坑过程或环境细节、以后可能反复查询时，写细节区并在 MEMORY.md 核心区留一行指针，例如「详见 memory_detail:eta-灵屏」。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("list").put("read").put("write").put("delete"))
                                    .put("description", "list 列主题；read 读正文；write 覆盖写；delete 删除。"),
                            )
                            .put(
                                "name",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 64)
                                    .put("description", "主题名（不含扩展名），如 eta-灵屏。read/write/delete 必填。"),
                            )
                            .put(
                                "title",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 120)
                                    .put("description", "write 时写入的标题行，便于 list 直接识别。"),
                            )
                            .put(
                                "content",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 20000)
                                    .put("description", "write 时的正文（Markdown）。"),
                            )
                            .put(
                                "max_chars",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 1000)
                                    .put("maximum", 32000)
                                    .put("description", "read 最多返回字符数，默认 8000。"),
                            ),
                    )
                    .put("required", JSONArray().put("action"))
                    .put("additionalProperties", false),
            ),
        )
        if (!writable) return
        tools
            .put(
                AgentToolSchema.function(
                    name = "memory_write",
                    description = "Atomically update persistent MEMORY.md. Store only durable cross-conversation facts, preferences, relationships, and ongoing project context; never store secrets, credentials, verification codes, or transient requests. Keep '# 核心记忆' concise, correct stale facts, and prefer replacing an existing section over blindly appending duplicates. 核心区只写结论、关键参数和指针，长内容用 memory_detail 落细节并在核心区留一行「详见 memory_detail:主题」。Use the revision from the most recent memory_write or memory_get result, or the session memory snapshot if neither has run.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("replace_range")
                                                .put("append")
                                                .put("clear"),
                                        )
                                        .put("description", "replace_range edits inclusive 1-based lines; append adds a distinct section; clear removes all memory."),
                                )
                                .put(
                                    "revision",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("minLength", 64)
                                        .put("maxLength", 64)
                                        .put("description", "Exact SHA-256 revision from the most recent memory_write or memory_get result, or the session memory snapshot."),
                                )
                                .put(
                                    "start_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "Required for replace_range; inclusive 1-based first line."),
                                )
                                .put(
                                    "end_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "Required for replace_range; inclusive 1-based last line."),
                                )
                                .put(
                                    "content",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", AgentMemoryStore.MAX_WRITE_CONTENT_CHARS)
                                        .put("description", "Replacement or appended Markdown, at most 3500 characters. Empty content deletes a replace_range."),
                                ),
                        )
                        .put("required", JSONArray().put("mode").put("revision")),
                ),
            )
    }
}
