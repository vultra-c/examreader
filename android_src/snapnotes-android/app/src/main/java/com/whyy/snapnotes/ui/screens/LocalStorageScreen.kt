package com.whyy.snapnotes.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.ui.components.FolderCreationDialog
import com.whyy.snapnotes.ui.components.MoreMenu
import com.whyy.snapnotes.ui.components.AppCard
import com.whyy.snapnotes.ui.components.AppDialog
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.popup.WindowDropdownPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.pressable
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地文件夹信息。
 *
 * @param name 文件夹显示名
 * @param path 文件夹绝对路径
 */
data class LocalFolder(
    val name: String,
    val path: String
)

/**
 * 本地知识点 JSON 文件信息。
 *
 * @param name 文件名（含扩展名）
 * @param path 文件绝对路径
 * @param size 文件大小（字节）
 * @param lastModified 最后修改时间（epoch 毫秒）
 */
data class LocalFile(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long
)

/**
 * 本地存储库页面。
 *
 * 以「文件夹 + JSON 文件」列表的形式管理手机本地的知识点文件，并支持导入手环：
 * - 顶部 [TopAppBar] 提供返回、刷新与 [MoreMenu]（创建文件夹等）
 * - 面包屑展示当前路径，点击任意层级可快速跳转
 * - 文件夹点击进入，长按弹出「重命名 / 删除」菜单
 * - 文件展示图标、名称、大小与修改日期，右侧「导入手环」按钮一键推送
 * - 空目录展示空状态引导
 *
 * 列表项使用 [AnimatedVisibility] 配合 fadeIn/fadeOut 实现平滑出现动画。
 *
 * @param currentPath 当前所在目录路径
 * @param folders 当前目录下的子文件夹列表
 * @param files 当前目录下的知识点文件列表
 * @param onBackClick 返回（退出页面或返回上级，由调用方决定）
 * @param onFolderClick 进入指定路径的文件夹（也用于面包屑跳转）
 * @param onCreateFolder 在当前目录下创建文件夹（参数为文件夹名）
 * @param onImportToBand 将指定文件导入手环
 * @param onDeleteFile 删除指定文件或文件夹
 * @param onRenameFile 重命名指定文件或文件夹（参数为文件与新名称）
 * @param onRefresh 刷新当前目录
 * @param modifier 修饰符
 */
@Composable
fun LocalStorageScreen(
    currentPath: String,
    folders: List<LocalFolder>,
    files: List<LocalFile>,
    onBackClick: () -> Unit,
    onFolderClick: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onImportToBand: (java.io.File) -> Unit,
    onDeleteFile: (java.io.File) -> Unit,
    onRenameFile: (java.io.File, String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBackClick() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // 对话框状态
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var renameInitial by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var deleteName by remember { mutableStateOf("") }
    var deleteIsFolder by remember { mutableStateOf(false) }

    val isEmpty = folders.isEmpty() && files.isEmpty()
    val headerText = if (isEmpty) {
        "本地存储"
    } else {
        "本地存储 · ${folders.size} 文件夹 / ${files.size} 文件"
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "本地存储库",
                largeTitle = "本地存储库",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.padding(start = 6.dp)) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, modifier = Modifier.padding(end = 2.dp)) {
                        Icon(imageVector = MiuixIcons.Refresh, contentDescription = "刷新")
                    }
                    MoreMenu(onCreateFolder = { showCreateFolderDialog = true })
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
                bottom = 24.dp
            )
        ) {
            // 面包屑：当前路径
            item {
                BreadcrumbBar(
                    currentPath = currentPath,
                    onSegmentClick = onFolderClick
                )
            }

            item {
                SmallTitle(
                    text = headerText,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (isEmpty) {
                item {
                    EmptyStateCard(onCreate = { showCreateFolderDialog = true })
                }
            } else {
                // 文件夹列表
                items(folders, key = { "folder_${it.path}" }) { folder ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(250)),
                        exit = fadeOut(tween(250))
                    ) {
                        FolderItemCard(
                            folder = folder,
                            onClick = { onFolderClick(folder.path) },
                            onRename = {
                                renameTarget = File(folder.path)
                                renameInitial = folder.name
                            },
                            onDelete = {
                                deleteTarget = File(folder.path)
                                deleteName = folder.name
                                deleteIsFolder = true
                            }
                        )
                    }
                }

                // 文件列表
                items(files, key = { "file_${it.path}" }) { file ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(250)),
                        exit = fadeOut(tween(250))
                    ) {
                        FileItemCard(
                            file = file,
                            onImport = { onImportToBand(File(file.path)) },
                            onRename = {
                                renameTarget = File(file.path)
                                renameInitial = file.name
                            },
                            onDelete = {
                                deleteTarget = File(file.path)
                                deleteName = file.name
                                deleteIsFolder = false
                            }
                        )
                    }
                }

                // 底部「新建文件夹」主操作按钮
                item {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { showCreateFolderDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .pressable(interactionSource = null, indication = SinkFeedback()),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Add,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "新建文件夹",
                            color = MiuixTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }

    // 创建文件夹对话框
    if (showCreateFolderDialog) {
        FolderCreationDialog(
            show = true,
            onConfirm = { name ->
                showCreateFolderDialog = false
                onCreateFolder(name)
            },
            onDismiss = { showCreateFolderDialog = false }
        )
    }

    // 重命名对话框
    renameTarget?.let { target ->
        LocalRenameDialog(
            initialName = renameInitial,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                onRenameFile(target, newName)
                renameTarget = null
            }
        )
    }

    // 删除确认对话框
    deleteTarget?.let { target ->
        LocalDeleteConfirmDialog(
            name = deleteName,
            isFolder = deleteIsFolder,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                onDeleteFile(target)
                deleteTarget = null
            }
        )
    }
}

/** 面包屑中的一个路径层级。 */
private data class PathSegment(val name: String, val path: String)

/**
 * 面包屑导航条：横向可滚动地展示 [currentPath] 的各层级，
 * 点击任意层级（除当前层外）通过 [onSegmentClick] 跳转。
 */
@Composable
private fun BreadcrumbBar(
    currentPath: String,
    onSegmentClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val segments = remember(currentPath) {
        if (currentPath.isBlank()) emptyList()
        else {
            val temp = ArrayDeque<PathSegment>()
            var current: File? = File(currentPath)
            while (current != null && current.name.isNotEmpty()) {
                temp.addFirst(PathSegment(current.name, current.path))
                current = current.parentFile
            }
            temp.toList()
        }
    }

    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        containerColor = MiuixTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = MiuixIcons.Folder,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .size(18.dp)
                    .padding(start = 16.dp, end = 4.dp)
            )
            if (segments.isEmpty()) {
                Text(
                    text = if (currentPath.isBlank()) "根目录" else currentPath,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 16.dp)
                )
            } else {
                segments.forEachIndexed { index, segment ->
                    if (index > 0) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp).rotate(180f),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    val isLast = index == segments.lastIndex
                    Text(
                        text = segment.name,
                        style = MiuixTheme.textStyles.body2,
                        color = if (isLast) MiuixTheme.colorScheme.onSurface
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .then(
                                if (!isLast) Modifier.clickable { onSegmentClick(segment.path) }
                                else Modifier
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

/**
 * 文件夹列表项：[BasicComponent] 展示文件夹图标与名称，
 * 单击进入，长按弹出「重命名 / 删除」上下文菜单。
 */
@Composable
private fun FolderItemCard(
    folder: LocalFolder,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showContextMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showContextMenu = true }
                ),
            onClick = null,
            containerColor = MiuixTheme.colorScheme.surfaceContainer
        ) {
            BasicComponent(
                title = folder.name,
                summary = "文件夹",
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Folder,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                },
                endActions = {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).rotate(180f),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                },
                onClick = null
            )
        }

        WindowDropdownPopup(
            entries = listOf(
                DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = "重命名",
                            icon = {
                                Icon(
                                    imageVector = MiuixIcons.Edit,
                                    contentDescription = null,
                                    modifier = it,
                                    tint = MiuixTheme.colorScheme.primary
                                )
                            },
                            onClick = onRename
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
                            onClick = onDelete
                        )
                    )
                )
            ),
            show = showContextMenu,
            onDismiss = { showContextMenu = false },
            onDismissFinished = {},
            maxHeight = null,
            dropdownColors = DropdownDefaults.dropdownColors(),
            collapseOnSelection = true
        )
    }
}

/**
 * 文件列表项：文件图标 + 名称 + 大小/日期，右侧「导入手环」按钮；
 * 长按弹出「重命名 / 删除」上下文菜单。
 */
@Composable
private fun FileItemCard(
    file: LocalFile,
    onImport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showContextMenu by remember { mutableStateOf(false) }
    val sizeStr = remember(file.size) { formatFileSize(file.size) }
    val dateStr = remember(file.lastModified) { formatDate(file.lastModified) }

    Box(modifier = modifier.fillMaxWidth()) {
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = { showContextMenu = true },
                    onLongClick = { showContextMenu = true }
                ),
            onClick = null,
            containerColor = MiuixTheme.colorScheme.surfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.File,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$sizeStr · $dateStr",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1
                    )
                }
                Button(
                    onClick = onImport,
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Icon(
                        imageVector = MiuixIcons.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "导入手环",
                        color = MiuixTheme.colorScheme.onPrimary,
                        style = MiuixTheme.textStyles.body2
                    )
                }
            }
        }

        WindowDropdownPopup(
            entries = listOf(
                DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = "重命名",
                            icon = {
                                Icon(
                                    imageVector = MiuixIcons.Edit,
                                    contentDescription = null,
                                    modifier = it,
                                    tint = MiuixTheme.colorScheme.primary
                                )
                            },
                            onClick = onRename
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
                            onClick = onDelete
                        )
                    )
                )
            ),
            show = showContextMenu,
            onDismiss = { showContextMenu = false },
            onDismissFinished = {},
            maxHeight = null,
            dropdownColors = DropdownDefaults.dropdownColors(),
            collapseOnSelection = true
        )
    }
}

/**
 * 空状态：当前目录无文件夹与文件时，展示图标与「新建文件夹」引导。
 */
@Composable
private fun EmptyStateCard(onCreate: () -> Unit, modifier: Modifier = Modifier) {
    AppCard(
        modifier = modifier.padding(horizontal = 12.dp),
        containerColor = MiuixTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = MiuixIcons.Folder,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "本地暂无知识点文件",
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "将知识点 JSON 文件放入本地目录，或新建文件夹开始整理，导入手环后即可随时查看",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onCreate,
                modifier = Modifier.pressable(interactionSource = null, indication = SinkFeedback()),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(text = "新建文件夹", color = MiuixTheme.colorScheme.onPrimary)
            }
        }
    }
}

/**
 * 重命名对话框：输入新名称后确认。
 */
@Composable
private fun LocalRenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialName) }
    AppDialog(
        show = true,
        title = "重命名",
        summary = "请输入新的名称",
        confirmText = "确定",
        dismissText = "取消",
        onConfirm = {
            if (text.isNotBlank()) onConfirm(text.trim())
        },
        onDismiss = onDismiss,
        onDismissRequest = onDismiss
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = "名称"
        )
    }
}

/**
 * 删除确认对话框：文件夹会提示连带删除内部内容。
 */
@Composable
private fun LocalDeleteConfirmDialog(
    name: String,
    isFolder: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AppDialog(
        show = true,
        title = "确认删除",
        summary = "「$name」将被永久删除" +
            if (isFolder) "，文件夹内所有内容也将一并删除" else "",
        confirmText = "删除",
        dismissText = "取消",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        onDismissRequest = onDismiss
    )
}

/**
 * 将字节数格式化为人类可读的大小（B / KB / MB / GB / TB）。
 */
private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = size.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${size} B"
    else String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}

/**
 * 将 epoch 毫秒格式化为 yyyy-MM-dd。
 */
private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0) return "-"
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
}