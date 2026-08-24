package com.whyy.snapnotes.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.ui.viewmodel.PushRecord
import com.whyy.snapnotes.ui.viewmodel.toReadableBytes
import com.whyy.snapnotes.ui.components.MoreMenu
import com.whyy.snapnotes.ui.components.FolderCreationDialog
import com.whyy.snapnotes.ui.components.AppCard
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    records: List<PushRecord>,
    onRepush: (PushRecord) -> Unit,
    onDeleteRequest: (PushRecord) -> Unit,
    onBatchDeleteRequest: (List<PushRecord>) -> Unit,
    onEditRecord: (PushRecord) -> Unit,      // 新增：编辑历史记录
    onCreateFolder: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showFolderDialog by remember { mutableStateOf(false) }

    if (showFolderDialog) {
        FolderCreationDialog(
            show = true,
            onConfirm = { name ->
                showFolderDialog = false
                onCreateFolder(name)
            },
            onDismiss = { showFolderDialog = false }
        )
    }

    val selectAllState = when {
        records.isEmpty() -> ToggleableState.Off
        selectedIds.size == records.size -> ToggleableState.On
        selectedIds.isEmpty() -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = if (selectionMode) "已选 ${selectedIds.size} 条" else "推送历史",
                largeTitle = if (selectionMode) "已选 ${selectedIds.size} 条" else "推送历史",
                scrollBehavior = scrollBehavior,
                actions = {
                    if (selectionMode) {
                        Checkbox(
                            state = selectAllState,
                            onClick = {
                                selectedIds = if (selectAllState == ToggleableState.On) {
                                    emptySet()
                                } else {
                                    records.map { it.id }.toSet()
                                }
                            }
                        )
                        IconButton(
                            onClick = {
                                val selected = records.filter { it.id in selectedIds }
                                if (selected.isNotEmpty()) onBatchDeleteRequest(selected)
                            }
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = "批量删除",
                                tint = if (selectedIds.isEmpty()) {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                } else {
                                    MiuixTheme.colorScheme.primary
                                }
                            )
                        }
                        IconButton(
                            onClick = {
                                selectionMode = false
                                selectedIds = emptySet()
                            }
                        ) {
                            Icon(imageVector = MiuixIcons.Close, contentDescription = "退出多选")
                        }
                    } else {
                        MoreMenu(
                            onCreateFolder = { showFolderDialog = true }
                        )
                    }
                }
            )
        },
        popupHost = {}
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .scrollEndHaptic(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = 40.dp
            )
        ) {
            // 说明卡
            item {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    containerColor = MiuixTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Info,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "以下是本机记录的推送历史",
                                style = MiuixTheme.textStyles.title4,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "手环会把所有知识点合并成单一仓库按科目名+编号增量合并" +
                                        "（同编号不覆盖，无法单独删除条目）。这里的删除只清掉本机缓存与记录，" +
                                        "不会删除手环上已导入的内容；点击卡片可加载到编辑器。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }

            if (records.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = MiuixIcons.File,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "还没有推送过的文件",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "推送成功的文件会在这里留一份，便于重新编辑或推送",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            } else {
                item {
                    SmallTitle(
                        text = "共 ${records.size} 条记录",
                        modifier = Modifier.padding(start = 24.dp)
                    )
                }
                items(records, key = { it.id }) { record ->
                    val isSelected = record.id in selectedIds
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        selectedIds = if (isSelected) {
                                            selectedIds - record.id
                                        } else {
                                            selectedIds + record.id
                                        }
                                    } else {
                                        onEditRecord(record)
                                    }
                                },
                                onLongClick = {
                                    // 长按直接进入多选并选中这条记录。
                                    selectionMode = true
                                    selectedIds = selectedIds + record.id
                                }
                            ),
                        onClick = null,
                        containerColor = MiuixTheme.colorScheme.surfaceContainer
                    ) {
                        BasicComponent(
                            title = record.fileName,
                            summary = run {
                                val subj = record.subjects.joinToString("、").let {
                                    if (it.length > 24) it.take(24) + "…" else it
                                }
                                val subjLine = if (subj.isNotBlank()) "科目：$subj" else "科目：未知"
                                "$subjLine\n大小：${record.fileSize.toReadableBytes()}\n时间：${timeFmt.format(Date(record.pushedAt))}"
                            },
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.File,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 16.dp)
                                )
                            },
                            endActions = {
                                if (selectionMode) {
                                    Checkbox(
                                        state = ToggleableState(isSelected),
                                        onClick = null
                                    )
                                } else {
                                    WindowIconDropdownMenu(
                                        entries = listOf(
                                            DropdownEntry(
                                                items = listOf(
                                                    DropdownItem(
                                                        text = "重新推送",
                                                        icon = {
                                                            Icon(
                                                                imageVector = MiuixIcons.Refresh,
                                                                contentDescription = null,
                                                                modifier = it,
                                                                tint = MiuixTheme.colorScheme.primary
                                                            )
                                                        },
                                                        onClick = { onRepush(record) }
                                                    ),
                                                    DropdownItem(
                                                        text = "删除",
                                                        icon = {
                                                            Icon(
                                                                imageVector = MiuixIcons.Delete,
                                                                contentDescription = null,
                                                                modifier = it,
                                                                tint = MiuixTheme.colorScheme.error
                                                            )
                                                        },
                                                        onClick = { onDeleteRequest(record) }
                                                    )
                                                )
                                            )
                                        ),
                                        collapseOnSelection = true
                                    ) {
                                        Icon(
                                            imageVector = MiuixIcons.More,
                                            contentDescription = "更多操作"
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}