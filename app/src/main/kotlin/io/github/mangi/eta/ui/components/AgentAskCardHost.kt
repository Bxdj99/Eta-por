package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.display.dashboard.AgentConfirmGuard
import io.github.mangi.eta.agent.display.dashboard.PendingApproval
import io.github.mangi.eta.agent.display.dashboard.PromptOption
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import com.itos.coui.theme.CouiTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 会话内的提问卡 / 审批卡宿主。
 *
 * 之前 AgentConfirmGuard 只把待办放进 mutablePendings 并发通知，
 * 但没有任何界面订阅它——用户点通知跳回会话后看不到任何东西，
 * choose/chooseMany/skip 也从来没有调用方，等于整个 ask_user 链路断在 UI 这一层。
 *
 * 这里负责：
 *  1) 订阅 pendings，只展示属于当前会话、未过期的那张；
 *  2) 单选 / 多选 / 自由输入 / 跳过四个出口，分别接 choose / chooseMany / chooseFreeform / skip；
 *  3) 回灌文本作为一条用户消息交给会话继续跑。
 */
@Composable
internal fun AgentAskCardHost(
    conversationKey: String?,
    onSubmitReply: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pendings by AgentConfirmGuard.pendings.collectAsState()
    val pending = remember(pendings, conversationKey) {
        pendings.firstOrNull { item ->
            if (item.expired()) return@firstOrNull false
            // 归属匹配要容忍空 owner：
            // AgentConfirmGuard.ask() 里 owner 取自 conversationOfRun(runId).ifBlank { currentConversation }，
            // 而 bindConversation 目前没有任何调用方，currentConversation 恒为 ""。
            // 早先这里要求 item.conversationKey == conversationKey（真实会话 id），
            // 于是永远匹配不上，卡根本渲染不出来。
            conversationKey == null ||
                item.conversationKey.isBlank() ||
                item.conversationKey == conversationKey
        }
    } ?: return

    val isAsk = pending.source == PendingApproval.SOURCE_ASK
    var selected by remember(pending.token) { mutableStateOf<Set<String>>(emptySet()) }
    var freeform by remember(pending.token) { mutableStateOf("") }

    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val shape = remember { RoundedCornerShape(16.dp) }
    val background = if (isCoui) {
        if (CouiTheme.isDark) androidx.compose.ui.graphics.Color(0xFF1F2227)
        else androidx.compose.ui.graphics.Color(0xFFFFFFFF)
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(background, shape)
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = if (isAsk) "需要你拍板" else "待确认操作",
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = pending.title,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (pending.body.isNotBlank()) {
                Text(
                    text = pending.body,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            // 选项区：多选时先勾再提交，单选点一下就走。
            pending.options.forEach { option ->
                OptionRow(
                    option = option,
                    multi = pending.allowMultiple,
                    checked = option.id in selected,
                    onClick = {
                        if (pending.allowMultiple) {
                            selected = if (option.id in selected) selected - option.id else selected + option.id
                        } else {
                            val reply = AgentConfirmGuard.choose(context, pending.token, option.id, pending.conversationKey)
                            if (reply != null) onSubmitReply(reply)
                        }
                    },
                )
            }

            // 自由输入：选项覆盖不到时用它兜底。
            if (isAsk) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(if (isCoui) androidx.compose.ui.graphics.Color(0xFF2A2D33) else MiuixTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = freeform,
                        onValueChange = { freeform = it },
                        singleLine = true,
                        textStyle = MiuixTheme.textStyles.body2.copy(
                            color = MiuixTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                    )
                    if (freeform.isNotBlank()) {
                        Text(
                            text = "发送",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                val extra = if (pending.allowMultiple) selected.toList() else emptyList()
                                val reply = AgentConfirmGuard.chooseFreeform(
                                    context = context,
                                    token = pending.token,
                                    text = freeform,
                                    extraIds = extra,
                                    conversationId = pending.conversationKey,
                                )
                                if (reply != null) {
                                    freeform = ""
                                    onSubmitReply(reply)
                                }
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pending.allowMultiple && selected.isNotEmpty()) {
                    Text(
                        text = "确认选中的 ${selected.size} 项",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.primaryContainer, RoundedCornerShape(50))
                            .clickable {
                                val reply = AgentConfirmGuard.chooseMany(
                                    context = context,
                                    token = pending.token,
                                    optionIds = selected.toList(),
                                    conversationId = pending.conversationKey,
                                )
                                if (reply != null) onSubmitReply(reply)
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "跳过",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .clickable {
                            val reply = AgentConfirmGuard.skip(context, pending.token, pending.conversationKey)
                            if (reply != null) onSubmitReply(reply)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun OptionRow(
    option: PromptOption,
    multi: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val indicator = if (multi) {
        if (checked) "☑" else "☐"
    } else {
        "›"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (checked) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surface,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = indicator,
            style = MiuixTheme.textStyles.body2,
            color = if (checked) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.label,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (option.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = option.description,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
