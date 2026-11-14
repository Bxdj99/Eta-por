package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工作台账的工具描述。
 *
 * 放在框架层而不是提示词里，是因为「记一步」「收尾自检」必须是可执行动作：
 * 只有工具回执才能证明台账真的写进去了，纯提示词约束不了模型。
 */
internal object AgentLedgerToolCatalog {

    const val TOOL_NAME = "task_ledger"

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = TOOL_NAME,
                description = "执行过程的落盘台账，用于长链条任务和收尾自检。" +
                    "record：完成一步就记一条，必须带 evidence（命令回显、读回的版本号、trace 行、after 节点变化等真凭实据）；" +
                    "没有回执时 status 会被强制降为 unconfirmed，这类状态只允许「重新观察再决定」，不允许原样重放动作。" +
                    "review：收尾前调一次，列出所有没有回执的条目，按它决定哪些能在回答里说「已完成」、哪些只能说「未确认」。" +
                    "步骤超过 5 步、或涉及写文件／装包／发消息／下单这类有外部副作用的任务，应当边做边记。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("record").put("claim").put("confirm").put("review").put("read").put("clear"))
                                    .put(
                                        "description",
                                        "record 记一步；claim 在动手前登记一次操作（同一 claim_key 第二次调用会被拒绝，这是防重复执行的硬闸门）；" +
                                            "confirm 用一个独立的 claim_key 为该条目补第二份取证（数值验收要求两次独立取证）；" +
                                            "review 收尾自检；read 读全文；clear 清空当前台账。",
                                    ),
                            )
                            .put(
                                "claim_key",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 120)
                                    .put("description", "claim 必填：这次操作的唯一标识，例如 send-msg-42 或 install-por49。"),
                            )
                            .put(
                                "task_id",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "台账标识，默认 default。同一次任务的多步用同一个值。"),
                            )
                            .put(
                                "step",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 200)
                                    .put("description", "record 必填：这一步做了什么（一句话）。"),
                            )
                            .put(
                                "status",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("ok").put("failed").put("unconfirmed"))
                                    .put("description", "这一步的结果。ok 必须配 evidence，否则会被降为 unconfirmed。"),
                            )
                            .put(
                                "evidence",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 600)
                                    .put("description", "回执原文：命令输出、读回的值、trace 片段、after 里的节点变化。"),
                            )
                            .put(
                                "tool",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "可选：这一步用的工具名，便于回看。"),
                            )
                            .put(
                                "dedupe_key",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "可选：幂等键。同一个 key 已存在时不再追加，避免重试把台账灌满。"),
                            ),
                    )
                    .put("required", JSONArray().put("action"))
                    .put("additionalProperties", false),
            ),
        )
    }
}
