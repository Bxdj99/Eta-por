package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiInputDialog
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 分类选择器里的一个条目（id 只在会话映射里出现，不参与展示）。 */
internal data class CategoryOption(val id: String, val name: String)

/**
 * 「移到分类」选择层。
 *
 * COUI 走 [CouiNoticeDialog] 的 content 槽（和确认层同一套材质/背板），Miuix 走 [WindowDialog]；
 * 两种样式共用同一份行内容，避免同一功能出现两套视觉语言。
 */
@Composable
internal fun ConversationCategoryPickerDialog(
    conversationTitle: String?,
    options: List<CategoryOption>,
    selectedCategoryId: String?,
    onPick: (String?) -> Unit,
    onCreateAndPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (conversationTitle == null) return
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val shownTitle = conversationTitle.ifBlank { stringResource(R.string.conversation_category_move) }
    val closeText = stringResource(R.string.action_close)

    val body: @Composable () -> Unit = {
        CategoryPickerRows(
            subtitle = shownTitle,
            options = options,
            selectedCategoryId = selectedCategoryId,
            creating = creating,
            newName = newName,
            onNameChange = { newName = it },
            onStartCreate = { creating = true },
            onCancelCreate = { creating = false; newName = "" },
            onConfirmCreate = {
                val name = newName.trim()
                if (name.isNotEmpty()) {
                    onCreateAndPick(name)
                    creating = false
                    newName = ""
                    onDismiss()
                }
            },
            onPick = { id ->
                onPick(id)
                onDismiss()
            },
        )
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiNoticeDialog(
                visible = true,
                title = stringResource(R.string.conversation_category_move),
                message = null,
                confirmText = closeText,
                onDismissRequest = onDismiss,
            ) {
                body()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = true,
                cornerRadius = DialogDefaults.CornerRadius,
                title = stringResource(R.string.conversation_category_move),
                onDismissRequest = onDismiss,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    body()
                    Spacer(Modifier.width(0.dp))
                }
            }
        }
    }
}

@Composable
private fun CategoryPickerRows(
    subtitle: String,
    options: List<CategoryOption>,
    selectedCategoryId: String?,
    creating: Boolean,
    newName: String,
    onNameChange: (String) -> Unit,
    onStartCreate: () -> Unit,
    onCancelCreate: () -> Unit,
    onConfirmCreate: () -> Unit,
    onPick: (String?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = subtitle,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
        )
        CategoryPickRow(
            label = stringResource(R.string.conversation_category_uncategorized),
            selected = selectedCategoryId == null,
            onClick = { onPick(null) },
        )
        options.forEach { option ->
            CategoryPickRow(
                label = option.name,
                selected = selectedCategoryId == option.id,
                onClick = { onPick(option.id) },
            )
        }
        if (options.isEmpty() && !creating) {
            Text(
                text = stringResource(R.string.conversation_category_empty),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        CreateCategoryRow(
            creating = creating,
            newName = newName,
            onNameChange = onNameChange,
            onStart = onStartCreate,
            onCancel = onCancelCreate,
            onConfirm = onConfirmCreate,
        )
        Spacer(Modifier.width(0.dp))
    }
}

@Composable
private fun CategoryPickRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            fontSize = 16.sp,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            MiuixIcon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun CreateCategoryRow(
    creating: Boolean,
    newName: String,
    onNameChange: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!creating) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onStart)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixIcon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.conversation_move_category_create),
                style = MiuixTheme.textStyles.body1,
                fontSize = 16.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            TextField(
                value = newName,
                onValueChange = onNameChange,
                label = stringResource(R.string.conversation_move_category_name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onCancel,
                    minWidth = 0.dp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.action_confirm),
                    onClick = onConfirm,
                    minWidth = 0.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 分类管理入口：长按侧栏分类标题后弹出的两条操作。 */
@Composable
internal fun ConversationCategoryActionsDialog(
    categoryName: String?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
) {
    if (categoryName == null) return
    val closeText = stringResource(R.string.action_close)
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            CategoryActionRow(
                text = "上移",
                enabled = canMoveUp,
                onClick = onMoveUp,
            )
            CategoryActionRow(
                text = "下移",
                enabled = canMoveDown,
                onClick = onMoveDown,
            )
            CategoryActionRow(
                text = stringResource(R.string.conversation_category_rename),
                onClick = onRename,
            )
            CategoryActionRow(
                text = stringResource(R.string.conversation_category_delete),
                destructive = true,
                onClick = onDelete,
            )
        }
    }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiNoticeDialog(
                visible = true,
                title = categoryName,
                message = null,
                confirmText = closeText,
                onDismissRequest = onDismiss,
            ) {
                body()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = true,
                cornerRadius = DialogDefaults.CornerRadius,
                title = categoryName,
                onDismissRequest = onDismiss,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    body()
                    TextButton(
                        text = closeText,
                        onClick = onDismiss,
                        minWidth = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryActionRow(
    text: String,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body1,
        fontSize = 16.sp,
        color = when {
            !enabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
            destructive -> MiuixTheme.colorScheme.error
            else -> MiuixTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** 分类改名输入层：COUI 用输入材质层，Miuix 用 WindowDialog + TextField。 */
@Composable
internal fun ConversationCategoryNameDialog(
    visible: Boolean,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    var name by remember { mutableStateOf(initialName) }
    val saveText = stringResource(R.string.action_save)
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiInputDialog(
                visible = true,
                title = stringResource(R.string.conversation_category_rename),
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.conversation_move_category_name),
                confirmText = saveText,
                confirmEnabled = name.isNotBlank(),
                onConfirm = { if (name.isNotBlank()) onConfirm(name.trim()) },
                onDismissRequest = onDismiss,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = true,
                cornerRadius = DialogDefaults.CornerRadius,
                title = stringResource(R.string.conversation_category_rename),
                onDismissRequest = onDismiss,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.conversation_move_category_name),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = onDismiss,
                            minWidth = 0.dp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = saveText,
                            onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                            minWidth = 0.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** 删除分类确认层：只解散归属，不删会话。 */
@Composable
internal fun ConversationCategoryDeleteDialog(
    categoryName: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (categoryName == null) return
    val message = stringResource(R.string.conversation_category_delete_confirm)
    val confirmText = stringResource(R.string.action_delete)
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiConfirmDialog(
                visible = true,
                title = categoryName,
                message = message,
                confirmText = confirmText,
                destructive = true,
                onConfirm = onConfirm,
                onDismissRequest = onDismiss,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = true,
                cornerRadius = DialogDefaults.CornerRadius,
                title = categoryName,
                onDismissRequest = onDismiss,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = onDismiss,
                            minWidth = 0.dp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = confirmText,
                            onClick = onConfirm,
                            minWidth = 0.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
