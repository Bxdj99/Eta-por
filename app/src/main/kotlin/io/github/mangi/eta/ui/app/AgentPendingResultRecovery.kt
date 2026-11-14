package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi

/** 将 Runtime outbox 的结果幂等折叠回 App 会话。 */
internal object AgentPendingResultRecovery {
    data class Outcome(
        val state: AgentChatHomeUiState,
        val alreadyApplied: Boolean,
    )

    fun apply(
        state: AgentChatHomeUiState,
        runId: String,
        result: AgentRuntimeWire.RunResult,
        promptSupplement: AgentUiHandoffPayload.Supplement? = null,
        supplements: List<AgentUiHandoffPayload.Supplement>,
    ): Outcome {
        if (result.operation == AgentRuntimeWire.OP_REWRITE_REPLY || runId in state.roleplayMessages.pendingRewrites) {
            return Outcome(RoleplayConversationReducer.applyRewrite(state, runId, result), runId in state.appliedRuntimeRunIds)
        }
        val stateWithSupplements = state.copy(messages = mergeSupplements(
            runId, listOfNotNull(promptSupplement) + supplements, state.messages,
        ))
        val content = result.content.takeIf { result.ok && it.isNotBlank() }
        val history = AgentRuntimeHistoryReducer.apply(
            state = stateWithSupplements,
            runId = runId,
            snapshot = result.contextSnapshot?.let { snapshot ->
                snapshot.copy(consumedTranscriptMessages = snapshot.consumedTranscriptMessages?.let {
                    it + if (promptSupplement != null) 1 else 0
                })
            },
            retainPendingSupplements = !result.ok || result.contextSnapshot != null,
            additions = listOfNotNull(
                promptSupplement?.let { supplement ->
                    AgentModelClient.buildUserHistoryMessage(
                        text = supplement.text,
                        images = emptyList(),
                    ).copy(messageId = supplementMessageId(runId, supplement.index))
                }
            ) + result.transcript,
        )
        if (history.alreadyApplied) return Outcome(state, alreadyApplied = true)
        if (result.operation == AgentRuntimeWire.OP_COMPACT) {
            return Outcome(history.state.copy(isStreaming = false, isCompacting = false,
                messages = AgentRunMessageProjector.mergeCompactionResultNotice(
                    runId = runId,
                    messages = state.messages,
                    ok = result.ok,
                    detail = if (result.ok) "上下文压缩完成" else result.error ?: "上下文压缩失败",
                )), false)
        }

        val messagesWithResult = stateWithSupplements.messages
            .filterNot { it is SystemNoticeMessageUi && it.id == interruptedNoticeId(runId) }
            .toMutableList()
            .also { messages ->
            val assistantIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages, includeNotices = true)
            val resultId = AgentRunMessageProjector.resultFallbackId(runId, messages)
            val targetRound = (messages.getOrNull(assistantIndex) as? AgentMessageUi)
                ?.id
                ?.assistantRound(runId)
            val sameRoundBlocks = targetRound?.let { round ->
                messages.count { message ->
                    message is AgentMessageUi && message.id.assistantRound(runId) == round
                }
            } ?: 0
            val completedMessage: AgentChatMessageUi = when {
                content != null -> AgentMessageUi(
                    id = resultId,
                    content = if (sameRoundBlocks > 1) {
                        (messages[assistantIndex] as AgentMessageUi).content.ifBlank { content }
                    } else {
                        content
                    },
                    isStreaming = false,
                    renderMarkdown = true,
                )
                result.ok -> SystemNoticeMessageUi(
                    id = resultId,
                    code = SystemNoticeCode.EmptyResult,
                )
                else -> SystemNoticeMessageUi(
                    id = resultId,
                    code = SystemNoticeCode.RuntimeFailed,
                    detail = result.error,
                )
            }
            if (assistantIndex >= 0) {
                messages[assistantIndex] = completedMessage.copyWithId(messages[assistantIndex].id)
            } else {
                messages += completedMessage
            }
        }
        return Outcome(
            state = history.state.copy(
                messages = mergeSupplements(
                    runId = runId,
                    supplements = listOfNotNull(promptSupplement) + supplements,
                    messages = messagesWithResult,
                    beforeLatestAssistant = true,
                ),
                history = history.state.history,
                appliedRuntimeRunIds = history.state.appliedRuntimeRunIds,
                isStreaming = false,
            ).let { RoleplayConversationReducer.linkRun(it, runId) },
            alreadyApplied = false,
        )
    }

    private fun AgentChatMessageUi.copyWithId(id: String): AgentChatMessageUi = when (this) {
        is AgentMessageUi -> copy(id = id)
        is SystemNoticeMessageUi -> copy(id = id)
        else -> this
    }

    fun mergeSupplements(
        runId: String,
        supplements: List<AgentUiHandoffPayload.Supplement>,
        messages: List<AgentChatMessageUi>,
        beforeLatestAssistant: Boolean = false,
    ): List<AgentChatMessageUi> {
        var updated = messages
        supplements.sortedBy { it.index }.forEach { supplement ->
            val id = supplementMessageId(runId, supplement.index)
            if (updated.any { it.id == id }) return@forEach
            val userMessage = UserMessageUi(id = id, content = supplement.text)

            // 插队消息要落在「用户此刻看到的最后一行」之后。
            // 判断依据不能只看 isStreaming：UserSupplementReceived 到达 UI 的时机
            // 可能早于该轮的第一个 text delta（那时消息还不是 streaming 态），
            // 那样会退化到「整条追加到末尾」，插话就永远落在上一段回复之后。
            // 改为：只要这是本 run 的最后一条 assistant 消息，且这个 run 还没结束，
            // 就把它就地切开。
            val lastAssistantIndex = updated.indexOfLast {
                it is AgentMessageUi && it.isAssistantForRun(runId)
            }
            val isTail = lastAssistantIndex == updated.indexOfLast { it is AgentMessageUi }
            val shouldSplit = lastAssistantIndex >= 0 && isTail && (
                (updated[lastAssistantIndex] as AgentMessageUi).isStreaming || !beforeLatestAssistant
            )
            updated = if (shouldSplit) {
                val live = updated[lastAssistantIndex] as AgentMessageUi
                val hasContent = live.content.isNotBlank()
                // 不能沿用 assistant-<run>-<round>-<index> 前缀：isAssistantMessageForRound
                // 用 startsWith("<prefix><round>-") 判断，加后缀仍会被当成本 round 的消息，
                // 导致收尾/替换同时打到两段上。改用独立命名空间。
                val headId = "supplement-split-$id"
                val head = live.copy(id = headId, isStreaming = false)
                val tail = live.copy(
                    content = "",
                    isStreaming = hasContent,
                    usage = null,
                )
                val list = updated.toMutableList()
                // 有内容才保留前半段；空消息直接替换掉，避免留下空气泡。
                // 只有这一轮还在写（isStreaming）时才补 tail 承接后续增量，
                // 否则会留一个永远空着的消息。
                val keepTail = live.isStreaming
                if (hasContent) {
                    list[lastAssistantIndex] = head
                    list.add(lastAssistantIndex + 1, userMessage)
                    if (keepTail) list.add(lastAssistantIndex + 2, tail)
                } else {
                    list[lastAssistantIndex] = userMessage
                    if (keepTail) list.add(lastAssistantIndex + 1, tail)
                }
                list
            } else {
                val assistantIndex = updated.indexOfLast {
                    it is AgentMessageUi && it.isAssistantForRun(runId) && beforeLatestAssistant
                }
                if (assistantIndex >= 0) {
                    updated.toMutableList().also { it.add(assistantIndex, userMessage) }
                } else {
                    updated + userMessage
                }
            }
        }
        return updated
    }

    private fun AgentChatMessageUi.isAssistantForRun(runId: String): Boolean =
        this is AgentMessageUi &&
            (id == "assistant-$runId" || id.startsWith(assistantMessagePrefix(runId)))

    private fun assistantMessagePrefix(runId: String): String = "assistant-$runId-"

    private fun String.assistantRound(runId: String): Int? =
        removePrefix(assistantMessagePrefix(runId))
            .takeIf { it != this }
            ?.substringBefore('-')
            ?.toIntOrNull()

    internal fun supplementMessageId(runId: String, index: Int): String =
        "user-$runId-supplement-$index"

    private fun interruptedNoticeId(runId: String): String = "interrupted-$runId"

}
