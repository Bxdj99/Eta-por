package io.github.mangi.eta.agent.runtime

import java.security.MessageDigest

/** 入口、归档与浏览器共同使用的会话身份；外部归档标识保持已发布算法不变。 */
internal object AgentConversationIdentity {
    const val EXTERNAL_ARCHIVE_PREFIX = "archive-"

    /** 自动任务会话；可续聊，与只读的外部归档区分开。 */
    const val AUTOMATION_PREFIX = "automation-"

    /** 自动任务来源标识；该来源的会话允许在聊天里直接续聊。 */
    const val AUTOMATION_SOURCE = "automation"

    fun fromHandoff(handoff: AgentRuntimeWire.EntryHandoff?, fallbackRunId: String): String {
        if (handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            AgentUiHandoffPayload.from(handoff.payload).conversationId.takeIf(String::isNotBlank)?.let { return it }
        } else if (handoff != null) {
            AgentExternalArchivePayload.from(handoff.payload)?.let { payload ->
                return archiveConversationId(handoff.source, payload.conversationKey)
            }
        }
        return "run:$fallbackRunId"
    }

    fun archiveConversationId(source: String, conversationKey: String): String {
        if (source == AUTOMATION_SOURCE) {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$source:$conversationKey".toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
            return AUTOMATION_PREFIX + digest
        }
        val prefix = if (source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) "assistant-" else EXTERNAL_ARCHIVE_PREFIX
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$source:$conversationKey".toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return prefix + digest
    }
}
