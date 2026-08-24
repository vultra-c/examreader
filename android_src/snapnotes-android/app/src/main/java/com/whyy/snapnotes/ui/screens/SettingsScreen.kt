package com.whyy.snapnotes.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.ui.components.FolderCreationDialog
import com.whyy.snapnotes.ui.components.MoreMenu
import com.whyy.snapnotes.ui.components.AppCard
import com.whyy.snapnotes.ui.components.AppToggle
import com.whyy.snapnotes.ui.theme.AppearanceMode
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun SettingsScreen(
    appearanceMode: AppearanceMode,
    onAppearanceModeChange: (AppearanceMode) -> Unit,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    useBuiltinFileManager: Boolean,
    onUseBuiltinFileManagerChange: (Boolean) -> Unit,
    lastExportDirSummary: String?,
    onPickExportDir: () -> Unit,
    onOpenAbout: () -> Unit,
    onResetFirstSyncConfirm: () -> Unit,
    onCreateFolder: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "设置",
                largeTitle = "设置",
                scrollBehavior = scrollBehavior,
                actions = {
                    MoreMenu(
                        onCreateFolder = { showFolderDialog = true }
                    )
                }
            )
        },
        popupHost = {}
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .overScrollVertical()
                .scrollEndHaptic(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = 40.dp
            )
        ) {
            item {
                SmallTitle(text = "外观", modifier = Modifier.padding(top = 12.dp))
                AppCard(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    containerColor = MiuixTheme.colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        WindowDropdownPreference(
                            title = "应用主题",
                            summary = "选择浅色、深色或跟随系统",
                            items = AppearanceMode.entries.map { it.label },
                            selectedIndex = AppearanceMode.entries.indexOf(appearanceMode).coerceAtLeast(0),
                            onSelectedIndexChange = { index ->
                                onAppearanceModeChange(AppearanceMode.entries[index])
                            }
                        )
                        BasicComponent(
                            title = "动态取色",
                            summary = "开启后按系统壁纸生成整套配色（Monet）",
                            endActions = {
                                AppToggle(
                                    checked = dynamicColor,
                                    onCheckedChange = onDynamicColorChange
                                )
                            }
                        )
                    }
                }
            }
            item {
                SmallTitle(text = "导入")
                AppCard(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    containerColor = MiuixTheme.colorScheme.surfaceContainer
                ) {
                    BasicComponent(
                        title = "使用内置文件管理器",
                        summary = "开启后用应用内文件浏览器选择 JSON；关闭后调用系统文件选择器",
                        endActions = {
                            AppToggle(
                                checked = useBuiltinFileManager,
                                onCheckedChange = onUseBuiltinFileManagerChange
                            )
                        }
                    )
                }
            }
            item {
                SmallTitle(text = "导出")
                AppCard(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    containerColor = MiuixTheme.colorScheme.surfaceContainer
                ) {
                    BasicComponent(
                        title = "导出目录",
                        summary = if (lastExportDirSummary != null) {
                            "最近导出到：$lastExportDirSummary"
                        } else {
                            "未导出过；在编辑器点击「导出 JSON 文件」可选择目录"
                        },
                        startAction = {
                            top.yukonga.miuix.kmp.basic.Icon(
                                imageVector = MiuixIcons.Folder,
                                contentDescription = "导出目录",
                                tint = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                        },
                        onClick = onPickExportDir
                    )
                }
            }
            item {
                SmallTitle(text = "其他")
                AppCard(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    containerColor = MiuixTheme.colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        BasicComponent(
                            title = "重置首次同步确认",
                            summary = "下次推送时重新显示 Vela 同步注意事项的倒计时确认弹窗",
                            startAction = {
                                top.yukonga.miuix.kmp.basic.Icon(
                                    imageVector = MiuixIcons.Reset,
                                    contentDescription = "重置",
                                    tint = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 16.dp)
                                )
                            },
                            onClick = onResetFirstSyncConfirm
                        )
                        BasicComponent(
                            title = "关于",
                            summary = "开发者信息、参考项目",
                            startAction = {
                                top.yukonga.miuix.kmp.basic.Icon(
                                    imageVector = MiuixIcons.Info,
                                    contentDescription = "关于",
                                    tint = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 16.dp)
                                )
                            },
                            onClick = onOpenAbout
                        )
                    }
                }
            }
        }
    }
}
