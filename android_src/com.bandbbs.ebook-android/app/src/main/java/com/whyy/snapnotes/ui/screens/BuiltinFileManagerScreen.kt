package com.whyy.snapnotes.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.whyy.snapnotes.ui.utils.BuiltinFileManagerPaths
import com.whyy.snapnotes.ui.utils.ImportPathPreset
import com.whyy.snapnotes.ui.viewmodel.MAX_IMPORT_FILE_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import com.whyy.snapnotes.ui.components.AppCard
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

private data class FileItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val summary: String
)

/** 闪念小抄仅导入 JSON。 */
private val SUPPORTED_IMPORT_EXTENSIONS = setOf("json")

enum class FileManagerPickMode { File, Directory }

private fun isSupportedImportFile(file: File): Boolean {
    if (file.isDirectory) return true
    val extension = file.extension.lowercase()
    return extension in SUPPORTED_IMPORT_EXTENSIONS
}

private fun hasFileManagerAccess(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * 内置文件管理器：仿照 ebook-android 的 BuiltinFileManagerScreen，改成单选 + 仅 JSON。
 * 调用方传入 onPick：拿到用户选中的本地文件后，转成 Uri 回到既有的推送/编辑导入链路。
 */
@Composable
fun BuiltinFileManagerScreen(
    onBackClick: () -> Unit,
    onPick: (file: File) -> Unit,
    modifier: Modifier = Modifier,
    pickMode: FileManagerPickMode = FileManagerPickMode.File,
    onPickDir: ((dir: File) -> Unit)? = null,
    onPickDirTitle: String = "选择导出目录"
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var hasFileAccess by remember { mutableStateOf(hasFileManagerAccess(context)) }
    var selectedFile by remember { mutableStateOf<File?>(null) }

    val availablePresets = remember(hasFileAccess) {
        if (hasFileAccess) BuiltinFileManagerPaths.resolvedDefaults(context) else emptyList()
    }

    val readExternalStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "未授予存储权限，无法访问内置文件管理器", Toast.LENGTH_SHORT)
                .show()
        }
        hasFileAccess = hasFileManagerAccess(context)
    }

    val allFilesAccessSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasFileAccess = hasFileManagerAccess(context)
        if (!hasFileAccess) {
            Toast.makeText(context, "未授予所有文件访问权限", Toast.LENGTH_SHORT).show()
        }
    }

    val supportedMimeTypes = remember {
        arrayOf("application/json", "application/octet-stream")
    }

    val systemFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // 走外部传入的固定入口：把 Uri 复制回应用后直接 onPick，沿用既有 onFilePicked 链路。
            onPickUri(context, uri, onPick, onBackClick)
        } else {
            onBackClick()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasFileAccess && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                readExternalStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            hasFileAccess = hasFileManagerAccess(context)
        }
    }

    LaunchedEffect(availablePresets, hasFileAccess) {
        // 选目录模式不回退系统文件选择器（系统选择器只能选文件），保留空白页让用户用快捷路径进入。
        if (pickMode == FileManagerPickMode.File && hasFileAccess && availablePresets.isEmpty()) {
            systemFilePickerLauncher.launch("application/json")
            onBackClick()
        }
    }

    val onConfirm: () -> Unit = {
        if (pickMode != FileManagerPickMode.Directory) {
            selectedFile?.let { onPick(it) }
        }
    }

    Scaffold(modifier = modifier, popupHost = {}) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background),
        ) {
            if (!hasFileAccess) {
                FileManagerPermissionPage(
                    isLegacyAndroid = Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
                    onRequestPermission = {
                        when {
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activity != null -> {
                                val packageUri = Uri.parse("package:${activity.packageName}")
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    packageUri
                                )
                                runCatching {
                                    allFilesAccessSettingsLauncher.launch(intent)
                                }.onFailure {
                                    allFilesAccessSettingsLauncher.launch(
                                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    )
                                }
                            }

                            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.READ_EXTERNAL_STORAGE
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    hasFileAccess = true
                                } else {
                                    readExternalStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                            }

                            else -> {
                                Toast.makeText(context, "无法申请权限", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onUseSystemPicker = {
                        systemFilePickerLauncher.launch("application/json")
                    },
                    onBack = onBackClick
                )
            } else {
                FileManagerPage(
                    availablePresets = availablePresets,
                    selectedFile = selectedFile,
                    onSelectFileChange = { selectedFile = it },
                    onConfirm = onConfirm,
                    onOpenSystemPicker = {
                        systemFilePickerLauncher.launch("application/json")
                    },
                    onBack = onBackClick,
                    pickMode = pickMode,
                    onPickDir = onPickDir,
                    pickDirTitle = onPickDirTitle,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.background)
                )
            }
        }
    }
}

/** 把 SAF 的 content Uri 复制成私有目录文件后再交给 onPick，保证后续读取稳定。 */
private fun onPickUri(
    context: android.content.Context,
    uri: Uri,
    onPick: (File) -> Unit,
    onBackClick: () -> Unit
) {
    val cache = File(context.cacheDir, "snapnotes_import_${System.currentTimeMillis()}.json")
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            cache.outputStream().use { output -> input.copyTo(output) }
        }
        if (!cache.exists() || cache.length() == 0L) {
            Toast.makeText(context, "无法读取所选文件", Toast.LENGTH_SHORT).show()
            onBackClick()
            return
        }
        if (cache.length() > MAX_IMPORT_FILE_BYTES) {
            cache.delete()
            Toast.makeText(context, "文件超过 50MB 上限", Toast.LENGTH_SHORT).show()
            onBackClick()
            return
        }
        onPick(cache)
    }.onFailure {
        Toast.makeText(context, "读取失败：${it.message}", Toast.LENGTH_SHORT).show()
        onBackClick()
    }
}

@Composable
private fun FileManagerPermissionPage(
    isLegacyAndroid: Boolean,
    onRequestPermission: () -> Unit,
    onUseSystemPicker: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "内置文件管理器",
                largeTitle = "内置文件管理器",
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.padding(start = 6.dp)) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isLegacyAndroid) {
                    "旧版安卓需要读取存储权限才能浏览内部存储目录"
                } else {
                    "需要所有文件访问权限才能浏览内部存储目录"
                },
                style = MiuixTheme.textStyles.body1,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isLegacyAndroid) {
                    "若拒绝权限，可直接改用系统文件选择器导入文件"
                } else {
                    "你仍然可以使用系统文件选择器导入文件"
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MiuixTheme.colorScheme.primary,
                shape = RoundedCornerShape(14.dp),
                onClick = onRequestPermission
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "授予存储权限", color = MiuixTheme.colorScheme.onPrimary)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MiuixTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(14.dp),
                onClick = onUseSystemPicker
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "使用系统文件选择器")
                }
            }
        }
    }
}

@Composable
private fun FileManagerPage(
    availablePresets: List<Pair<ImportPathPreset, File>>,
    selectedFile: File?,
    onSelectFileChange: (File?) -> Unit,
    onConfirm: () -> Unit,
    onOpenSystemPicker: () -> Unit,
    onBack: () -> Unit,
    pickMode: FileManagerPickMode = FileManagerPickMode.File,
    onPickDir: ((dir: File) -> Unit)? = null,
    pickDirTitle: String = "选择导出目录",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentDir by remember { mutableStateOf<File?>(null) }
    var searchQuery by remember(currentDir) { mutableStateOf("") }
    var pathInput by remember(currentDir) { mutableStateOf("") }
    var fileItems by remember(currentDir) { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember(currentDir) { mutableStateOf(false) }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // 选目录模式：进入即落到第一个可读快捷路径根，便于直接浏览/确认。
    LaunchedEffect(availablePresets, pickMode) {
        if (pickMode == FileManagerPickMode.Directory && currentDir == null) {
            val firstReadable = availablePresets.firstOrNull { it.second.exists() && it.second.canRead() }?.second
            if (firstReadable != null) currentDir = firstReadable
        }
    }

    LaunchedEffect(currentDir) {
        if (currentDir != null) {
            isLoading = true
            fileItems = withContext(Dispatchers.IO) {
                val files = currentDir!!.listFiles()
                    ?.filter { it.exists() && it.canRead() && isSupportedImportFile(it) }
                    ?.let { list -> if (pickMode == FileManagerPickMode.Directory) list.filter { it.isDirectory } else list }
                    ?: emptyList()
                files.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    .map {
                        FileItem(
                            file = it,
                            name = it.name,
                            isDirectory = it.isDirectory,
                            summary = if (it.isDirectory) "文件夹" else Formatter.formatFileSize(
                                context,
                                it.length()
                            )
                        )
                    }
            }
            isLoading = false
        } else {
            fileItems = emptyList()
        }
    }

    val parentDir = remember(currentDir, availablePresets) {
        if (currentDir == null) null
        else {
            val isRootPreset =
                availablePresets.any { it.second.absolutePath == currentDir?.absolutePath }
            if (isRootPreset) null else currentDir?.parentFile
        }
    }

    val navigateBack = {
        if (currentDir != null) {
            currentDir = parentDir
        } else {
            onBack()
        }
    }

    BackHandler(enabled = currentDir != null) {
        currentDir = parentDir
    }

    val normalizedQuery = searchQuery.trim()
    val normalizedQueryLower = normalizedQuery.lowercase()
    val isSuffixFilter = normalizedQueryLower.startsWith(".") && normalizedQueryLower.length > 1

    val filteredPresets = remember(availablePresets, normalizedQuery) {
        if (normalizedQuery.isBlank()) availablePresets
        else availablePresets.filter { (preset, file) ->
            preset.label.contains(normalizedQuery, ignoreCase = true) ||
                    file.absolutePath.contains(normalizedQuery, ignoreCase = true)
        }
    }

    val filteredFileItems = remember(fileItems, normalizedQueryLower, isSuffixFilter) {
        if (normalizedQueryLower.isBlank()) fileItems
        else fileItems.filter { item ->
            item.name.contains(normalizedQueryLower, ignoreCase = true) ||
                    (isSuffixFilter && !item.isDirectory && item.name.lowercase().endsWith(normalizedQueryLower))
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = if (pickMode == FileManagerPickMode.Directory) {
                    currentDir?.name ?: pickDirTitle
                } else {
                    currentDir?.name ?: "选择 JSON 文件"
                },
                largeTitle = if (pickMode == FileManagerPickMode.Directory) {
                    currentDir?.name ?: pickDirTitle
                } else {
                    currentDir?.name ?: "选择 JSON 文件"
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = navigateBack, modifier = Modifier.padding(start = 6.dp)) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    if (pickMode == FileManagerPickMode.Directory) {
                        AnimatedVisibility(
                            visible = currentDir != null,
                            enter = fadeIn() + slideInHorizontally { it / 2 },
                            exit = fadeOut() + slideOutHorizontally { it / 2 },
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    val dir = currentDir ?: return@IconButton
                                    if (dir.canWrite()) {
                                        runCatching { onPickDir?.invoke(dir) }
                                    } else {
                                        Toast.makeText(context, "该目录不可写，请选择其他目录", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Icon(imageVector = MiuixIcons.Send, contentDescription = "保存到此目录")
                            }
                        }
                    } else {
                        AnimatedVisibility(
                            visible = selectedFile != null,
                            enter = fadeIn() + slideInHorizontally { it / 2 },
                            exit = fadeOut() + slideOutHorizontally { it / 2 },
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onSelectFileChange(null) }) {
                                    Icon(
                                        imageVector = MiuixIcons.Close,
                                        contentDescription = "取消选择"
                                    )
                                }
                                IconButton(onClick = onConfirm) {
                                    Icon(imageVector = MiuixIcons.Send, contentDescription = "导入")
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(paddingValues),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding() + 24.dp
            )
        ) {
            if (currentDir == null && pickMode == FileManagerPickMode.File) {
                item {
                    TextField(
                        value = pathInput,
                        onValueChange = { pathInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        label = "输入路径并打开",
                        leadingIcon = {
                            Icon(
                                MiuixIcons.Folder,
                                contentDescription = "路径",
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (pathInput.isNotBlank()) {
                                    IconButton(
                                        onClick = { pathInput = "" },
                                        modifier = Modifier.padding(end = 6.dp)
                                    ) {
                                        Icon(MiuixIcons.Close, contentDescription = "清除")
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        val input = pathInput.trim()
                                        if (input.isEmpty()) {
                                            Toast.makeText(
                                                context,
                                                "请输入路径",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            return@IconButton
                                        }
                                        val target = File(input)
                                        when {
                                            !target.exists() -> Toast.makeText(
                                                context,
                                                "路径不存在",
                                                Toast.LENGTH_SHORT
                                            ).show()

                                            !target.isDirectory -> Toast.makeText(
                                                context,
                                                "该路径不是文件夹",
                                                Toast.LENGTH_SHORT
                                            ).show()

                                            !target.canRead() -> Toast.makeText(
                                                context,
                                                "无法读取该路径",
                                                Toast.LENGTH_SHORT
                                            ).show()

                                            else -> {
                                                currentDir = target
                                            }
                                        }
                                    }
                                ) {
                                    Icon(MiuixIcons.Send, contentDescription = "打开路径")
                                }
                            }
                        },
                        singleLine = true
                    )
                }
            }

            item {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    label = if (currentDir == null) "搜索路径/后缀（如 .json）..." else "搜索文件/后缀（如 .json）...",
                    leadingIcon = {
                        Icon(
                            MiuixIcons.Search,
                            contentDescription = "搜索",
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.padding(end = 12.dp)
                            ) {
                                Icon(MiuixIcons.Close, contentDescription = "清除")
                            }
                        }
                    } else null,
                    singleLine = true
                )
            }

            if (currentDir == null) {
                item { SmallTitle(text = "快捷路径") }
                item {
                    AppCard(
                        containerColor = MiuixTheme.colorScheme.surfaceContainer
                    ) {
                        // AppCard 内容为 Box 布局，多个条目必须用 Column 纵向排布，否则会重叠
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (filteredPresets.isEmpty()) {
                                if (availablePresets.isEmpty()) {
                                    BasicComponent(
                                        title = "未获取到存储权限",
                                        summary = "当前无法读取快捷路径，你可以使用下方的系统文件选择器导入文件"
                                    )
                                } else {
                                    BasicComponent(title = "未找到匹配路径")
                                }
                            } else {
                                filteredPresets.forEach { (preset, file) ->
                                    BasicComponent(
                                        title = preset.label,
                                        summary = file.absolutePath,
                                        startAction = {
                                            Icon(
                                                imageVector = MiuixIcons.Folder,
                                                contentDescription = "Folder",
                                                tint = MiuixTheme.colorScheme.primary,
                                                modifier = Modifier.padding(end = 16.dp)
                                            )
                                        },
                                        onClick = {
                                            currentDir = file
                                        }
                                    )
                                }
                            }
                            if (pickMode == FileManagerPickMode.File) {
                                BasicComponent(
                                    title = "系统文件选择器",
                                    summary = "调用系统文件选择器导入文件",
                                    startAction = {
                                        Icon(
                                            imageVector = MiuixIcons.File,
                                            contentDescription = "SystemFilePicker",
                                            tint = MiuixTheme.colorScheme.primary,
                                            modifier = Modifier.padding(end = 16.dp)
                                        )
                                    },
                                    onClick = onOpenSystemPicker
                                )
                            }
                        }
                    }
                }
            } else {
                item { SmallTitle(text = "当前路径: ${currentDir?.absolutePath}") }
                if (isLoading) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 64.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "加载中...",
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onBackgroundVariant
                            )
                        }
                    }
                } else if (filteredFileItems.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 64.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Close,
                                contentDescription = "Empty",
                                modifier = Modifier.size(64.dp),
                                tint = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (normalizedQueryLower.isBlank()) "这里什么都没有" else "未找到匹配文件",
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    item {
                        Column {
                            filteredFileItems.forEachIndexed { index, item ->
                                val isFirst = index == 0
                                val isLast = index == filteredFileItems.lastIndex
                                val shape = when {
                                    filteredFileItems.size == 1 -> RoundedCornerShape(16.dp)
                                    isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                                    isLast -> RoundedCornerShape(
                                        bottomStart = 16.dp,
                                        bottomEnd = 16.dp
                                    )
                                    else -> RectangleShape
                                }
                                val isSelected = selectedFile == item.file

                                AppCard(
                                    modifier = Modifier.clip(shape),
                                    shape = shape,
                                    onClick = {
                                        if (item.isDirectory) {
                                            currentDir = item.file
                                        } else {
                                            onSelectFileChange(if (isSelected) null else item.file)
                                        }
                                    },
                                    containerColor = MiuixTheme.colorScheme.surfaceContainer
                                ) {
                                    if (item.isDirectory) {
                                        BasicComponent(
                                            title = item.name,
                                            summary = item.summary,
                                            startAction = {
                                                Icon(
                                                    imageVector = MiuixIcons.Folder,
                                                    contentDescription = "Folder",
                                                    tint = MiuixTheme.colorScheme.onBackgroundVariant,
                                                    modifier = Modifier.padding(end = 16.dp)
                                                )
                                            },
                                            onClick = {
                                                currentDir = item.file
                                            }
                                        )
                                    } else {
                                        BasicComponent(
                                            title = item.name,
                                            summary = item.summary,
                                            startAction = {
                                                Icon(
                                                    imageVector = MiuixIcons.File,
                                                    contentDescription = "File",
                                                    tint = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackgroundVariant,
                                                    modifier = Modifier.padding(end = 16.dp)
                                                )
                                            },
                                            endActions = {
                                                Checkbox(
                                                    state = ToggleableState(isSelected),
                                                    onClick = null
                                                )
                                            },
                                            onClick = {
                                                onSelectFileChange(if (isSelected) null else item.file)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
