package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.ConcurrentHashMap

/**
 * 正在运行的子智能体 → 它自己的运行控制器。
 *
 * 子智能体不是一个独立 run，而是父 run 里的一个工具调用，自带 `childController`；
 * `AgentModelClient.complete` 的循环会通过它 poll 补充指令。所以"定向插话"就是
 * 把文本投进这个 childController —— 既不会打断当前批次，也不会被主线吃掉。
 */
internal object SubagentSteerRegistry {

    private class Entry(
        val conversationKey: String,
        val controller: AgentRunController,
    )

    private val running = ConcurrentHashMap<String, Entry>()

    fun register(conversationKey: String, subagentId: String, controller: AgentRunController) {
        if (subagentId.isBlank()) return
        running[subagentId] = Entry(conversationKey, controller)
    }

    fun unregister(subagentId: String) {
        if (subagentId.isBlank()) return
        running.remove(subagentId)
    }

    fun isRunning(subagentId: String): Boolean = running.containsKey(subagentId)

    /** 投递一条补充指令；返回 false 表示该子智能体已经结束，调用方应提示用户。 */
    fun steer(subagentId: String, text: String, conversationKey: String = ""): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val entry = running[subagentId] ?: return false
        if (conversationKey.isNotBlank() && entry.conversationKey != conversationKey) return false
        return entry.controller.steer(trimmed)
    }

    fun runningIds(): List<String> = running.keys.toList()
}
