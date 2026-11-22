package io.github.mangi.eta.ui.screens.skills

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import io.github.mangi.eta.agent.skill.SkillExporter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.canDeleteUserSkill
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun AgentSkillsScreen(
    state: AgentSkillsUiState,
    onAction: (AgentSkillsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteTarget by remember { mutableStateOf<SkillItemUi?>(null) }
    // 导出：先多选，再交给系统的「创建文档」选保存位置。
    var exportPicker by remember { mutableStateOf(false) }
    var exportSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var exportIds by remember { mutableStateOf<List<String>>(emptyList()) }
    val exportSaver = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null && exportIds.isNotEmpty()) {
            onAction(AgentSkillsAction.ExportSkills(exportIds, uri.toString()))
        }
    }
    val operationPending = state.isImporting || state.busySkillId != null
    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAction(AgentSkillsAction.ImportZip(uri.toString()))
    }
    val openZipPicker = {
        zipPicker.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
            ),
        )
    }

    val installed = state.skills.filter { it.installed }
    val builtinInstalled = installed.filter { it.source == "builtin" }
    val userInstalled = installed.filter { it.canDeleteUserSkill }
    val removed = state.skills.filter { !it.installed }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.route_skills),
                onBack = { onAction(AgentSkillsAction.NavigateBack) },
                modifier = modifier,
            ) {
                Group(title = stringResource(R.string.ui_install_087db6)) {
                    CouiPreference(
                        title = if (state.isImporting) stringResource(R.string.skills_checking_package) else stringResource(R.string.skills_import_zip),
                        summary = if (state.isImporting) {
                            stringResource(R.string.skills_installing_package)
                        } else {
                            stringResource(R.string.skills_choose_package)
                        },
                        trailing = if (state.isImporting) {
                            { InfiniteProgressIndicator(size = 20.dp) }
                        } else {
                            null
                        },
                        enabled = !operationPending,
                        onClick = openZipPicker,
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.skills_export),
                        summary = stringResource(R.string.skills_export_pick),
                        enabled = !operationPending && installed.isNotEmpty(),
                        onClick = {
                            exportSelection = emptySet()
                            exportPicker = true
                        },
                    )
                }

                if (builtinInstalled.isNotEmpty()) {
                    Group(title = stringResource(R.string.ui_built_in_skills_1ceedf)) {
                        builtinInstalled.forEachIndexed { index, skill ->
                            SkillSwitchRow(
                                skill = skill,
                                enabled = !operationPending,
                                onToggle = { enabled ->
                                    onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                },
                            )
                            if (index < builtinInstalled.lastIndex) {
                                Divider()
                            }
                        }
                    }
                }

                if (userInstalled.isNotEmpty()) {
                    Group(title = stringResource(R.string.ui_user_skills_748e7f)) {
                        userInstalled.forEachIndexed { index, skill ->
                            SkillSwitchRow(
                                skill = skill,
                                enabled = !operationPending,
                                onToggle = { enabled ->
                                    onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                },
                                onDelete = { deleteTarget = skill },
                            )
                            if (index < userInstalled.lastIndex) {
                                Divider()
                            }
                        }
                    }
                }

                if (removed.isNotEmpty()) {
                    Group(title = stringResource(R.string.ui_removed_4e5c49)) {
                        removed.forEachIndexed { index, skill ->
                            CouiPreference(
                                title = skill.name,
                                summary = stringResource(R.string.ui_click_to_reinstall_dc60de),
                                enabled = !operationPending,
                                onClick = {
                                    onAction(AgentSkillsAction.ReinstallBuiltin(skill.id))
                                },
                            )
                            if (index < removed.lastIndex) {
                                Divider()
                            }
                        }
                    }
                }

                if (state.skills.isEmpty() && state.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        InfiniteProgressIndicator(size = 24.dp)
                    }
                }
                if (state.skills.isEmpty() && !state.isLoading) {
                    ListEmptyState(
                        title = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                        summary = stringResource(R.string.skills_choose_package),
                        icon = Icons.Rounded.FolderZip,
                        action = {
                            CouiButton(
                                text = stringResource(R.string.skills_import_zip),
                                enabled = !operationPending,
                                role = CouiButtonRole.Primary,
                                onClick = openZipPicker,
                            )
                        },
                    )
                }

                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.route_skills),
                onBack = { onAction(AgentSkillsAction.NavigateBack) },
                modifier = modifier,
            ) {
                item(key = "zip-import") {
                    SmallTitle(stringResource(R.string.ui_install_087db6))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = if (state.isImporting) stringResource(R.string.skills_checking_package) else stringResource(R.string.skills_import_zip),
                            summary = if (state.isImporting) {
                                stringResource(R.string.skills_installing_package)
                            } else {
                                stringResource(R.string.skills_choose_package)
                            },
                            endActions = {
                                if (state.isImporting) {
                                    InfiniteProgressIndicator(size = 20.dp)
                                }
                            },
                            enabled = !operationPending,
                            onClick = openZipPicker,
                        )
                        top.yukonga.miuix.kmp.basic.HorizontalDivider()
                        ArrowPreference(
                            title = stringResource(R.string.skills_export),
                            summary = stringResource(R.string.skills_export_pick),
                            enabled = !operationPending && installed.isNotEmpty(),
                            onClick = {
                                exportSelection = emptySet()
                                exportPicker = true
                            },
                        )
                    }
                }

                if (builtinInstalled.isNotEmpty()) {
                    item(key = "builtin") {
                        SmallTitle(stringResource(R.string.ui_built_in_skills_1ceedf))
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            builtinInstalled.forEach { skill ->
                                SkillSwitchRow(
                                    skill = skill,
                                    enabled = !operationPending,
                                    onToggle = { enabled ->
                                        onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                    },
                                )
                            }
                        }
                    }
                }

                if (userInstalled.isNotEmpty()) {
                    item(key = "user") {
                        SmallTitle(stringResource(R.string.ui_user_skills_748e7f))
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            userInstalled.forEach { skill ->
                                SkillSwitchRow(
                                    skill = skill,
                                    enabled = !operationPending,
                                    onToggle = { enabled ->
                                        onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                    },
                                    onDelete = { deleteTarget = skill },
                                )
                            }
                        }
                    }
                }

                if (removed.isNotEmpty()) {
                    item(key = "removed") {
                        SmallTitle(stringResource(R.string.ui_removed_4e5c49))
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            removed.forEach { skill ->
                                BasicComponent(
                                    title = skill.name,
                                    summary = stringResource(R.string.ui_click_to_reinstall_dc60de),
                                    enabled = !operationPending,
                                    onClick = {
                                        onAction(AgentSkillsAction.ReinstallBuiltin(skill.id))
                                    },
                                )
                            }
                        }
                    }
                }

                if (state.skills.isEmpty() && state.isLoading) {
                    item(key = "loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            InfiniteProgressIndicator(size = 24.dp)
                        }
                    }
                }
                if (state.skills.isEmpty() && !state.isLoading) {
                    item(key = "empty") {
                        ListEmptyState(
                            title = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                            summary = stringResource(R.string.skills_choose_package),
                            icon = Icons.Rounded.FolderZip,
                            action = {
                                TextButton(
                                    text = stringResource(R.string.skills_import_zip),
                                    enabled = !operationPending,
                                    onClick = openZipPicker,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    state.replacement?.let { replacement ->
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.ui_replace_user_skills_250e98),
                    message = stringResource(R.string.skills_replace_summary, replacement.name, replacement.id),
                    confirmText = stringResource(R.string.skills_replace),
                    confirmEnabled = !operationPending,
                    onConfirm = { onAction(AgentSkillsAction.ConfirmZipReplacement) },
                    onDismissRequest = { onAction(AgentSkillsAction.CancelZipReplacement) },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = stringResource(R.string.ui_replace_user_skills_250e98),
                    summary = stringResource(R.string.skills_replace_summary, replacement.name, replacement.id),
                    onDismissRequest = { onAction(AgentSkillsAction.CancelZipReplacement) },
                ) {
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.skills_replace),
                        confirmEnabled = !operationPending,
                        onCancel = { onAction(AgentSkillsAction.CancelZipReplacement) },
                        onConfirm = { onAction(AgentSkillsAction.ConfirmZipReplacement) },
                    )
                }
            }
        }
    }

    // 导出多选：列出所有已安装技能，勾选后交给系统的创建文档选择器。
    if (exportPicker) {
        val exportable = installed
        val allSelected = exportable.isNotEmpty() && exportSelection.size == exportable.size
        WindowDialog(
            show = true,
            title = stringResource(R.string.skills_export),
            onDismissRequest = { exportPicker = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 技能多时必须限高滚动，否则底部的全选/导出按钮会被顶出屏幕。
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(exportable, key = { it.id }) { skill ->
                        ExportSkillRow(
                            name = skill.name,
                            id = skill.id,
                            description = skill.description,
                            checked = skill.id in exportSelection,
                            onToggle = {
                                exportSelection = if (skill.id in exportSelection) {
                                    exportSelection - skill.id
                                } else {
                                    exportSelection + skill.id
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = stringResource(R.string.skills_export_all),
                        onClick = {
                            exportSelection = if (allSelected) {
                                emptySet()
                            } else {
                                exportable.mapTo(mutableSetOf()) { it.id }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    TextButton(
                        text = stringResource(R.string.skills_export_selected, exportSelection.size),
                        enabled = exportSelection.isNotEmpty(),
                        onClick = {
                            exportPicker = false
                            exportIds = exportSelection.toList()
                            exportSaver.launch(SkillExporter.suggestedFileName(exportIds))
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    deleteTarget?.let { skill ->
        val onConfirmDelete: () -> Unit = {
            deleteTarget = null
            onAction(AgentSkillsAction.DeleteSkill(skill.id))
        }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.ui_delete_user_skills_f319a9),
                    message = stringResource(R.string.skills_delete_summary, skill.name),
                    confirmText = stringResource(R.string.ui_delete_3755f5),
                    confirmEnabled = !operationPending,
                    destructive = true,
                    onConfirm = onConfirmDelete,
                    onDismissRequest = { deleteTarget = null },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = stringResource(R.string.ui_delete_user_skills_f319a9),
                    summary = stringResource(R.string.skills_delete_summary, skill.name),
                    onDismissRequest = { deleteTarget = null },
                ) {
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.ui_delete_3755f5),
                        destructive = true,
                        confirmEnabled = !operationPending,
                        onCancel = { deleteTarget = null },
                        onConfirm = onConfirmDelete,
                    )
                }
            }
        }
    }

    state.notice?.let { notice ->
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiNoticeDialog(
                    visible = true,
                    title = notice.title,
                    message = notice.message,
                    confirmText = stringResource(R.string.ui_knew_cb63c6),
                    onDismissRequest = { onAction(AgentSkillsAction.DismissNotice) },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = notice.title,
                    summary = notice.message,
                    onDismissRequest = { onAction(AgentSkillsAction.DismissNotice) },
                ) {
                    TextButton(
                        text = stringResource(R.string.ui_knew_cb63c6),
                        onClick = { onAction(AgentSkillsAction.DismissNotice) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (notice.isError) {
                            ButtonDefaults.textButtonColors()
                        } else {
                            ButtonDefaults.textButtonColorsPrimary()
                        },
                    )
                }
            }
        }
    }
}

/** 导出多选里的一行：名称 + id + 勾选态。 */
@Composable
private fun ExportSkillRow(
    name: String,
    id: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        top.yukonga.miuix.kmp.basic.Text(
            text = if (checked) "☑" else "☐",
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            // 标题用 id（导入导出认的就是它），单行截断，避免长 id 换行把行高撑开。
            top.yukonga.miuix.kmp.basic.Text(
                text = id,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            val subtitle = description.ifBlank { name }
            if (subtitle.isNotBlank() && subtitle != id) {
                top.yukonga.miuix.kmp.basic.Text(
                    text = subtitle,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

