package com.whyy.snapnotes.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplayTransitionEffects
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import com.whyy.snapnotes.App
import com.whyy.snapnotes.logic.FormulaPngRenderer
import com.whyy.snapnotes.logic.InterHandshake
import com.whyy.snapnotes.notifications.ForegroundTransferService
import com.whyy.snapnotes.ui.components.EditorLoadErrorDialog
import com.whyy.snapnotes.ui.components.DraftRestoreDialog
import com.whyy.snapnotes.ui.components.ExportNameDialog
import com.whyy.snapnotes.ui.components.ExportResultDialog
import com.whyy.snapnotes.ui.components.FirstSyncConfirmDialog
import com.whyy.snapnotes.ui.components.HistoryBatchDeleteConfirmDialog
import com.whyy.snapnotes.ui.components.HistoryDeleteConfirmDialog
import com.whyy.snapnotes.ui.components.VersionIncompatibleDialog
import com.whyy.snapnotes.ui.screens.BuiltinFileManagerScreen
import com.whyy.snapnotes.ui.screens.AboutScreen
import com.whyy.snapnotes.ui.screens.AmadeusConfigScreen
import com.whyy.snapnotes.ui.screens.AmadeusChatScreen
import com.whyy.snapnotes.ui.screens.AmadeusContextScreen
import com.whyy.snapnotes.ui.screens.HistoryScreen
import com.whyy.snapnotes.ui.screens.HomeScreen
import com.whyy.snapnotes.ui.screens.ProgressScreen
import com.whyy.snapnotes.ui.screens.ResultScreen
import com.whyy.snapnotes.ui.screens.SettingsScreen
import com.whyy.snapnotes.ui.screens.TroubleshootScreen
import com.whyy.snapnotes.ui.theme.AppearanceMode
import com.whyy.snapnotes.ui.theme.SnapNotesTheme
import com.whyy.snapnotes.ui.viewmodel.AppScreen
import com.whyy.snapnotes.ui.viewmodel.SnapNotesViewModel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.whyy.snapnotes.ui.components.AppBackground
import com.whyy.snapnotes.ui.components.AppNavTab
import com.whyy.snapnotes.ui.components.AppNavigationBar
import com.whyy.snapnotes.ui.screens.StoreScreen
import com.whyy.snapnotes.ui.screens.StoreDetailScreen
import com.whyy.snapnotes.ui.screens.LocalStorageScreen
import com.whyy.snapnotes.ui.screens.EditorScreen
import com.whyy.snapnotes.data.StoreSubject
import com.whyy.snapnotes.data.StorePack

sealed interface Screen : NavKey {
    data object HomePager : Screen
    data object Progress : Screen
    data object Result : Screen
    data object FileManager : Screen
    data object About : Screen
    data object Troubleshoot : Screen
    data object AmadeusConfig : Screen
    data object AmadeusChat : Screen
    data object AmadeusContext : Screen
    data class StoreDetail(val pack: com.whyy.snapnotes.data.StorePack) : Screen
    data object LocalStorage : Screen
    data object Editor : Screen
}

class MainActivity : ComponentActivity() {

    private val viewModel: SnapNotesViewModel by viewModels()

    /** 文件选择器当前用途：false=选择待推送文件（默认），true=加载进编辑器。 */
    private var pickForEditor = false
    /** 内置文件管理器当前请求来源（与 pickForEditor 同义，供 Composable 侧读取下次入口）。 */
    private var pendingFileManagerForEditor = false

    /** 编辑器导出：内置文件管理器此刻是否作为「选导出目录」模式打开。
     *  为 true 时，文件管理器以选目录模式运行，确认目录后写入待导出 JSON。 */
    private var pendingExportSelection = false
    /** 设置页「导出目录」项打开的只是浏览模式：选目录模式，但不写文件，仅供用户查看/选择位置。 */
    private var pendingPickDirBrowse = false
    private var pendingExportJson: String? = null
    private var pendingExportFileName: String = "自定义知识点.json"

    /** 启动编辑器导出流程：先命名，再用内置文件管理器选目录写入。 */
    private fun startExportFlow() {
        // 触发命名对话框（Composable 侧读取 showExportName state）。
        showExportName.value = true
    }

    /** 命名确认后：打开内置文件管理器选目录模式，并把 JSON 落到所选目录。 */
    private fun launchExportDirPicker(json: String, fileName: String) {
        pendingExportJson = json
        pendingExportFileName = fileName
        pendingFileManagerForEditor = false   // 导出模式与「加载进编辑器」互斥
        pendingExportSelection = true
        navigateToFileManagerEntry?.invoke()
    }

    /** 由 Composable 注入的「打开内置文件管理器」入口（setContent 内赋值）。 */
    private var navigateToFileManagerEntry: (() -> Unit)? = null
    private var showExportName = androidx.compose.runtime.mutableStateOf(false)

    /** 编辑页公式预览渲染器（与推送共用同一实例，复用 WebView 保持输入流畅）。 */
    private var editorFormulaRenderer: com.whyy.snapnotes.logic.FormulaPngRenderer? = null

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                if (pickForEditor) viewModel.openEditorFromFile(it)
                else viewModel.onFilePicked(it)
            }
            pickForEditor = false
        }

    private fun launchFilePicker(forEditor: Boolean, navigateToFileManager: () -> Unit) {
        pickForEditor = forEditor
        pendingFileManagerForEditor = forEditor
        if (viewModel.useBuiltinFileManager.value) {
            // 应用内文件浏览器：作为页面入栈，走 NavDisplay 默认切换（与参考项目一致）。
            navigateToFileManager()
        } else {
            filePickerLauncher.launch("application/json")
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    /** 排查页蓝牙检测用：Android 12+ 运行时申请 BLUETOOTH_CONNECT。授权后排查页蓝牙项解 Checking。 */
    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.setTroubleshootBluetoothPermissionGranted(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val conn = InterHandshake(this, lifecycleScope)
        (application as App).conn = conn
        viewModel.setConnection(conn)
        val formulaRenderer = FormulaPngRenderer(this)
        viewModel.setFormulaRenderer(formulaRenderer)
        editorFormulaRenderer = formulaRenderer

        requestNotificationPermissionIfNeeded()
        observeForegroundServiceState()
        handleIncomingIntent(intent)

        setContent {
            val appearanceMode by viewModel.appearanceMode.collectAsState()
            val dynamicColor by viewModel.dynamicColor.collectAsState()

            val snackbarHostState = androidx.compose.runtime.remember { top.yukonga.miuix.kmp.basic.SnackbarHostState() }
            val snackbarMessage by viewModel.snackbarMessage.collectAsState()
            val scope = rememberCoroutineScope()
            androidx.compose.runtime.LaunchedEffect(snackbarMessage) {
                val msg = snackbarMessage ?: return@LaunchedEffect
                snackbarHostState.showSnackbar(msg)
                viewModel.dismissSnackbar()
            }

            SnapNotesTheme(
                appearanceMode = appearanceMode,
                dynamicColor = dynamicColor
            ) {
                val screen by viewModel.screen.collectAsState()
                val connectionState by viewModel.connectionState.collectAsState()
                val selectedFile by viewModel.selectedFile.collectAsState()
                val pushState by viewModel.pushState.collectAsState()
                val showFirstSyncConfirm by viewModel.showFirstSyncConfirm.collectAsState()
                val versionIncompatible by viewModel.versionIncompatibleState.collectAsState()
                val editorSubjects by viewModel.editorSubjects.collectAsState()
                val editorLoadError by viewModel.editorLoadError.collectAsState()
                val showDraftRestorePrompt by viewModel.showDraftRestorePrompt.collectAsState()
                val pushHistory by viewModel.pushHistory.collectAsState()
                val pendingHistoryDelete by viewModel.pendingHistoryDelete.collectAsState()
                val pendingHistoryBatchDelete by viewModel.pendingHistoryBatchDelete.collectAsState()
                val useBuiltinFileManager by viewModel.useBuiltinFileManager.collectAsState()
                val lastExportDirSummary by viewModel.lastExportDirSummary.collectAsState()
                val exportResult by viewModel.exportResult.collectAsState()
                val storageInfo by viewModel.storageInfo.collectAsState()
                val storageRefreshing by viewModel.storageRefreshing.collectAsState()
                val troubleshootState by viewModel.troubleshootState.collectAsState()
                val amadeus by viewModel.amadeus.collectAsState()
                val amadeusLastCall by viewModel.amadeusLastCall.collectAsState()
                val amadeusModels by viewModel.amadeusModels.collectAsState()
                val amadeusModelsLoading by viewModel.amadeusModelsLoading.collectAsState()

                // 「启用 Amadeus」开启 → 请求 Doze 电池优化白名单（后台/锁屏跑 LLM 网络的前提）。
                LaunchedEffect(Unit) {
                    viewModel.requestBatteryOptimization.collect {
                        requestIgnoreBatteryOptimizationsIfNeeded()
                    }
                }

                val scope = rememberCoroutineScope()
                val backStack = remember { mutableStateListOf<NavKey>(Screen.HomePager) }
                val currentScreen = backStack.lastOrNull() ?: Screen.HomePager
                val pagerState = rememberPagerState(
                    pageCount = { 4 },
                    initialPage = 0
                )

                val navigateTo = { target: Screen ->
                    if (backStack.lastOrNull() != target) {
                        backStack.add(target)
                    }
                }
                val navigateToHome = {
                    backStack.clear()
                    backStack.add(Screen.HomePager)
                }
                val navigateBack = {
                    if (backStack.size > 1) backStack.removeAt(backStack.size - 1)
                }
                val navigateToFileManager = {
                    if (backStack.lastOrNull() != Screen.FileManager) {
                        backStack.add(Screen.FileManager)
                    }
                }
                val navigateToAbout = {
                    if (backStack.lastOrNull() != Screen.About) {
                        backStack.add(Screen.About)
                    }
                }
                val navigateToTroubleshoot = {
                    if (backStack.lastOrNull() != Screen.Troubleshoot) {
                        backStack.add(Screen.Troubleshoot)
                    }
                }
                val navigateToAmadeus = {
                    if (backStack.lastOrNull() != Screen.AmadeusConfig) {
                        backStack.add(Screen.AmadeusConfig)
                    }
                }
                val navigateToAmadeusChat = {
                    if (backStack.lastOrNull() != Screen.AmadeusChat) {
                        backStack.add(Screen.AmadeusChat)
                    }
                }
                val navigateToAmadeusContext = {
                    if (backStack.lastOrNull() != Screen.AmadeusContext) {
                        backStack.add(Screen.AmadeusContext)
                    }
                }
                val navigateToStoreDetail = { pack: com.whyy.snapnotes.data.StorePack ->
                    backStack.add(Screen.StoreDetail(pack))
                    Unit
                }
                val navigateToLocalStorage = {
                    if (backStack.lastOrNull() != Screen.LocalStorage) {
                        backStack.add(Screen.LocalStorage)
                    }
                    Unit
                }
                val navigateToEditor = {
                    if (backStack.lastOrNull() != Screen.Editor) {
                        backStack.add(Screen.Editor)
                    }
                    Unit
                }
                // 注入给 Activity 侧的导出流程入口（已命名后用它打开选目录模式）。
                navigateToFileManagerEntry = navigateToFileManager

                LaunchedEffect(screen) {
                    when (screen) {
                        AppScreen.Progress -> navigateTo(Screen.Progress)
                        AppScreen.Result -> navigateTo(Screen.Result)
                        AppScreen.Home -> {
                            navigateToHome()
                            pagerState.animateScrollToPage(0)
                        }
                        AppScreen.Editor -> {
                            // 编辑器独立页面：导航到编辑器页面。
                            navigateToEditor()
                        }
                        else -> Unit
                    }
                }

                val showBottomBar = currentScreen is Screen.HomePager

                Box(modifier = Modifier.fillMaxSize()) {
                    AppBackground(
                        backgroundColor = MiuixTheme.colorScheme.background
                    )
                    Scaffold(
                        snackbarHost = {
                            top.yukonga.miuix.kmp.basic.SnackbarHost(state = snackbarHostState)
                        },
                        bottomBar = {
                            if (showBottomBar) {
                                AppNavigationBar(
                                    selectedTabIndex = { pagerState.currentPage },
                                    onTabSelected = { index ->
                                        when (index) {
                                            0 -> {
                                                viewModel.openHome()
                                                scope.launch { pagerState.animateScrollToPage(0) }
                                            }

                                            1 -> {
                                                viewModel.openStore()
                                                scope.launch { pagerState.animateScrollToPage(1) }
                                            }

                                            2 -> {
                                                viewModel.openHistory()
                                                scope.launch { pagerState.animateScrollToPage(2) }
                                            }

                                            3 -> {
                                                viewModel.openSettings()
                                                scope.launch { pagerState.animateScrollToPage(3) }
                                            }
                                        }
                                    },
                                    tabs = listOf(
                                        AppNavTab(MiuixIcons.Light.Home, "主页"),
                                        AppNavTab(MiuixIcons.Light.Store, "商店"),
                                        AppNavTab(MiuixIcons.Light.Recent, "历史"),
                                        AppNavTab(MiuixIcons.Light.Settings, "设置")
                                    ),
                                )
                            }
                        } ,
                    ) { paddingValues ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = if (showBottomBar) paddingValues.calculateBottomPadding() else 0.dp)
                        ) {
                            val entryProvider = remember(backStack) {
                                entryProvider<NavKey> {
                                    entry<Screen.HomePager> {
                                        HorizontalPager(
                                            state = pagerState,
                                            modifier = Modifier.fillMaxSize(),
                                            beyondViewportPageCount = 2,
                                            key = { it }
                                        ) { page ->
                                            when (page) {
                                                0 -> HomeScreen(
                                                    connectionState = connectionState,
                                                    selectedFile = selectedFile,
                                                    storageInfo = storageInfo,
                                                    storageRefreshing = storageRefreshing,
                                                    onRefreshStorage = viewModel::refreshStorageInfo,
                                                    onPickFile = {
                                                        launchFilePicker(
                                                            false,
                                                            navigateToFileManager
                                                        )
                                                    },
                                                    onStartPush = viewModel::startPushFromSelected,
                                                    onTroubleshoot = navigateToTroubleshoot,
                                                    onOpenAmadeusChat = navigateToAmadeusChat,
                                                    amadeusEnabled = amadeus.enabled,
                                                    amadeusReady = amadeus.isReady,
                                                    amadeusSummary = if (!amadeus.enabled) "未启用"
                                                        else if (amadeus.isReady) "已配置 · ${amadeus.model}"
                                                        else "配置不完整",
                                                    onCreateFolder = viewModel::createFolder,
                                                    onOpenLocalStorage = navigateToLocalStorage,
                                                    onOpenAmadeusConfig = navigateToAmadeus,
                                                    onNavigateToEditor = {
                                                        viewModel.openEditor()
                                                        navigateToEditor()
                                                    },
                                                    modifier = Modifier.fillMaxSize()
                                                )

                                                1 -> StoreScreen(
                                                    onPackClick = navigateToStoreDetail,
                                                    onCreateFolder = viewModel::createFolder,
                                                    onImportSubject = { subject ->
                                                        viewModel.importStoreSubject(subject)
                                                    },
                                                    onImportSubjects = { subjects ->
                                                        viewModel.importStoreSubjects(subjects)
                                                    },
                                                    modifier = Modifier.fillMaxSize()
                                                )

                                                2 -> HistoryScreen(
                                                    records = pushHistory,
                                                    onRepush = viewModel::repushRecord,
                                                    onDeleteRequest = viewModel::requestHistoryDelete,
                                                    onBatchDeleteRequest = viewModel::requestHistoryBatchDelete,
                                                    onEditRecord = { record ->
                                                        viewModel.openEditorFromCache(record)
                                                        navigateToEditor()
                                                    },
                                                    onCreateFolder = viewModel::createFolder,
                                                    modifier = Modifier.fillMaxSize()
                                                )

                                                3 -> SettingsScreen(
                                                    appearanceMode = appearanceMode,
                                                    onAppearanceModeChange = viewModel::setAppearanceMode,
                                                    dynamicColor = dynamicColor,
                                                    onDynamicColorChange = viewModel::setDynamicColor,
                                                    useBuiltinFileManager = useBuiltinFileManager,
                                                    onUseBuiltinFileManagerChange = viewModel::setUseBuiltinFileManager,
                                                    lastExportDirSummary = lastExportDirSummary,
                                                    onPickExportDir = {
                                                        pendingFileManagerForEditor = false
                                                        pendingExportSelection = false
                                                        pendingPickDirBrowse = true
                                                        navigateToFileManager()
                                                    },
                                                    onOpenAbout = navigateToAbout,
                                                    onResetFirstSyncConfirm = viewModel::resetFirstSyncConfirm,
                                                    onCreateFolder = viewModel::createFolder,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        }
                                    }
                                    entry<Screen.Progress> {
                                        ProgressScreen(
                                            pushState = pushState,
                                            onCancel = viewModel::cancelPush,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    entry<Screen.Result> {
                                        ResultScreen(
                                            pushState = pushState,
                                            onBackHome = viewModel::backHome,
                                            onRetry = viewModel::retry,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    entry<Screen.FileManager> {
                                        val dirMode = pendingExportSelection || pendingPickDirBrowse
                                        if (dirMode) {
                                            BuiltinFileManagerScreen(
                                                onBackClick = {
                                                    pendingExportSelection = false
                                                    pendingPickDirBrowse = false
                                                    navigateBack()
                                                },
                                                onPick = { /* 选目录模式下不会触发文件选择 */ },
                                                pickMode = com.whyy.snapnotes.ui.screens.FileManagerPickMode.Directory,
                                                onPickDir = { dir ->
                                                    val json = pendingExportJson
                                                    pendingExportSelection = false
                                                    pendingPickDirBrowse = false
                                                    if (json != null) {
                                                        viewModel.exportEditorJsonToDir(dir, json, pendingExportFileName)
                                                    } else {
                                                        // 设置页浏览模式：仅记录为最近导出目录并返回。
                                                        viewModel.rememberExportDir(dir)
                                                    }
                                                    pendingExportJson = null
                                                    navigateBack()
                                                },
                                                onPickDirTitle = if (pendingExportSelection) "保存到此目录" else "导出目录",
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            BuiltinFileManagerScreen(
                                                onBackClick = {
                                                    pendingFileManagerForEditor = false
                                                    navigateBack()
                                                },
                                                onPick = { file ->
                                                    if (pendingFileManagerForEditor) {
                                                        viewModel.onBuiltinFilePickedForEditor(file)
                                                    } else {
                                                        viewModel.onBuiltinFilePicked(file)
                                                    }
                                                    pendingFileManagerForEditor = false
                                                    navigateBack()
                                                },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    entry<Screen.StoreDetail> { screenEntry ->
                                        StoreDetailScreen(
                                            pack = screenEntry.pack,
                                            onBackClick = navigateBack,
                                            onImportAll = {
                                                viewModel.importStoreSubjects(screenEntry.pack.subjects)
                                                navigateBack()
                                            },
                                            onImportSelected = { selected ->
                                                viewModel.importStoreSubjects(selected)
                                                navigateBack()
                                            },
                                            onImportSingle = { subject ->
                                                viewModel.importStoreSubject(subject)
                                            },
                                            onEditSubject = { subject ->
                                                viewModel.loadEditorFromStoreSubject(subject)
                                                navigateToEditor()
                                            },
                                            onCreateFolder = viewModel::createFolder,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    entry<Screen.LocalStorage> {
                                        LaunchedEffect(Unit) { viewModel.refreshLocalStorage() }
                                        val localFolders by viewModel.localFolders.collectAsState()
                                        val localFiles by viewModel.localFiles.collectAsState()
                                        val localCurrentPath by viewModel.localCurrentPath.collectAsState()
                                        LocalStorageScreen(
                                            currentPath = localCurrentPath,
                                            folders = localFolders,
                                            files = localFiles,
                                            onBackClick = navigateBack,
                                            onFolderClick = viewModel::navigateLocalFolder,
                                            onCreateFolder = viewModel::createLocalFolder,
                                            onImportToBand = { file ->
                                                viewModel.pushFromFile(file)
                                                navigateBack()
                                            },
                                            onDeleteFile = viewModel::deleteLocalFile,
                                            onRenameFile = viewModel::renameLocalFile,
                                            onRefresh = viewModel::refreshLocalStorage,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    entry<Screen.Editor> {
                                        EditorScreen(
                                            subjects = editorSubjects,
                                            formulaRenderer = editorFormulaRenderer,
                                            onAddSubject = viewModel::addSubject,
                                            onRemoveSubject = viewModel::removeSubject,
                                            onUpdateSubjectName = viewModel::updateSubjectName,
                                            onAddEntry = viewModel::addEntry,
                                            onRemoveEntry = viewModel::removeEntry,
                                            onUpdateEntry = viewModel::updateEntry,
                                            onLoadFile = {
                                                launchFilePicker(true, navigateToFileManager)
                                            },
                                            onExportToFile = {
                                                startExportFlow()
                                            },
                                            onPushFile = {
                                                viewModel.pushFromString(
                                                    viewModel.getEditorJsonString(),
                                                    "自定义知识点.json"
                                                )
                                            },
                                            onBackClick = navigateBack,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    entry<Screen.About> {
                                        AboutScreen(
                                            onBackClick = navigateBack,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    entry<Screen.Troubleshoot> {
                                        // 进页面起三项监控；离开页面停轮询/解注册广播。
                                        LaunchedEffect(Unit) { viewModel.startTroubleshoot() }
                                        DisposableEffect(Unit) {
                                            onDispose { viewModel.stopTroubleshoot() }
                                        }
                                        TroubleshootScreen(
                                            state = troubleshootState,
                                            isConnected = connectionState.isConnected,
                                            onBackClick = navigateBack,
                                            onRequestBluetooth = {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    entry<Screen.AmadeusConfig> {
                                        AmadeusConfigScreen(
                                            config = amadeus,
                                            onEnabledChange = viewModel::setAmadeusEnabled,
                                            onBaseUrlChange = viewModel::setAmadeusBaseUrl,
                                            onApiKeyChange = viewModel::setAmadeusApiKey,
                                            onModelChange = viewModel::setAmadeusModel,
                                            onBackClick = navigateBack,
                                            onOpenContext = navigateToAmadeusContext,
                                            availableModels = amadeusModels,
                                            modelsLoading = amadeusModelsLoading,
                                            onFetchModels = viewModel::fetchAvailableModels,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    entry<Screen.AmadeusChat> {
                                        val phoneChatMessages by viewModel.phoneChatMessages.collectAsState()
                                        val phoneChatStatus by viewModel.phoneChatStatus.collectAsState()
                                        AmadeusChatScreen(
                                            messages = phoneChatMessages,
                                            chatStatus = phoneChatStatus,
                                            amadeusEnabled = amadeus.enabled,
                                            amadeusReady = amadeus.isReady,
                                            currentModel = amadeus.model,
                                            availableModels = amadeusModels,
                                            modelsLoading = amadeusModelsLoading,
                                            onModelChange = viewModel::setAmadeusModel,
                                            onFetchModels = viewModel::fetchAvailableModels,
                                            onSendMessage = { text, fileContent ->
                                                viewModel.sendPhoneChatMessage(text, fileContent)
                                            },
                                            onClearChat = viewModel::clearPhoneChat,
                                            onImportJson = { json ->
                                                viewModel.importJsonFromChat(json)
                                            },
                                            onOpenConfig = navigateToAmadeus,
                                            onBackClick = navigateBack,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    entry<Screen.AmadeusContext> {
                                        // 进页面拉一次快照（会话列表非 StateFlow，入页刷新即可）。
                                        var snapshots by remember { mutableStateOf(viewModel.amadeusSnapshots()) }
                                        LaunchedEffect(Unit) { snapshots = viewModel.amadeusSnapshots() }
                                        // lastCall 进入终态（Success/Failed）时新建/更新 test_ 会话，刷一次列表。
                                        LaunchedEffect(amadeusLastCall) { snapshots = viewModel.amadeusSnapshots() }
                                        AmadeusContextScreen(
                                            lastCall = amadeusLastCall,
                                            snapshots = snapshots,
                                            onDetail = { id ->
                                                viewModel.amadeusDetail(id).also {
                                                    // 详情返回后再刷一次快照（清空操作会改变列表）。
                                                    snapshots = viewModel.amadeusSnapshots()
                                                }
                                            },
                                            onClearSession = { id ->
                                                viewModel.clearAmadeusSession(id)
                                                snapshots = viewModel.amadeusSnapshots()
                                            },
                                            onClearAll = {
                                                viewModel.clearAllAmadeus()
                                                snapshots = viewModel.amadeusSnapshots()
                                            },
                                            onTestSend = { text ->
                                                viewModel.testSendAmadeus(text)
                                            },
                                            onExportLastReply = { viewModel.exportLastAmadeusReply() },
                                            onBackClick = navigateBack,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }

                            NavDisplay(
                                backStack = backStack,
                                entryProvider = entryProvider,
                                onBack = {
                                    if (backStack.size > 1) {
                                        when (backStack.last()) {
                                            is Screen.Progress -> viewModel.cancelPush()
                                            else -> Unit
                                        }
                                        backStack.removeAt(backStack.size - 1)
                                    } else {
                                        finish()
                                    }
                                },
                                // 页面转场：进栈右滑淡入（MIUI 风格），出栈反向。
                                transitionSpec = {
                                    (slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(380, easing = FastOutSlowInEasing))) togetherWith
                                            (slideOutHorizontally(tween(380, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(380, easing = FastOutSlowInEasing)))
                                },
                                popTransitionSpec = {
                                    (slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(380, easing = FastOutSlowInEasing))) togetherWith
                                            (slideOutHorizontally(tween(380, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(380, easing = FastOutSlowInEasing)))
                                },
                                transitionEffects = NavDisplayTransitionEffects(
                                    enableCornerClip = true,
                                    dimAmount = 0.2f,
                                    blockInputDuringTransition = true,
                                    popDirectionFollowsSwipeEdge = false
                                )
                            )
                        }
                        FirstSyncConfirmDialog(
                            show = showFirstSyncConfirm,
                            onConfirm = viewModel::confirmFirstSync,
                            onCancel = viewModel::cancelFirstSyncConfirm
                        )

                        DraftRestoreDialog(
                            show = showDraftRestorePrompt,
                            onRestore = viewModel::restoreEditorDraft,
                            onDiscard = viewModel::discardEditorDraft
                        )

                        HistoryDeleteConfirmDialog(
                            record = pendingHistoryDelete,
                            onConfirm = viewModel::confirmHistoryDelete,
                            onDismiss = viewModel::cancelHistoryDelete
                        )

                        HistoryBatchDeleteConfirmDialog(
                            records = pendingHistoryBatchDelete,
                            onConfirm = viewModel::confirmHistoryBatchDelete,
                            onDismiss = viewModel::cancelHistoryBatchDelete
                        )

                        VersionIncompatibleDialog(
                            state = versionIncompatible,
                            onDismiss = viewModel::dismissVersionIncompatible
                        )

                        EditorLoadErrorDialog(
                            message = editorLoadError,
                            onDismiss = viewModel::dismissEditorLoadError
                        )

                        ExportResultDialog(
                            result = exportResult,
                            onDismiss = viewModel::dismissExportResult
                        )

                        ExportNameDialog(
                            show = showExportName.value,
                            defaultName = "自定义知识点",
                            onDismiss = { showExportName.value = false },
                            onConfirm = { fileName ->
                                showExportName.value = false
                                launchExportDirPicker(
                                    viewModel.getEditorJsonString(),
                                    fileName
                                )
                            }
                        )
                    }
                } // Box 结束（Scaffold + 对话框）
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        lifecycleScope.launch {
            (application as App).conn?.destroy()?.await()
        }
        editorFormulaRenderer?.release()
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 请求加入 Doze 电池优化白名单。已在白名单则直接返回，否则弹系统授权框。
     *  Amadeus 启用时由 ViewModel 事件触发；后台/锁屏跑 LLM 网络的前提。 */
    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun observeForegroundServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.pushState.collect { pushState ->
                    if (pushState.isTransferring && !pushState.isFinished) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val progressPercent = if (pushState.progress > 0.0) {
                            (pushState.progress * 100).toInt().coerceIn(0, 100)
                        } else null
                        ForegroundTransferService.startService(
                            applicationContext,
                            progressPercent?.let { "$it%" } ?: "传输中",
                            pushState.statusText,
                            progressPercent
                        )
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        // 不再无条件停服务：传输结束后若 Amadeus 待命该启用，
                        // 切回待命通知（同 id 覆盖传输通知）；否则停。
                        viewModel.applyForegroundServiceAfterTransfer()
                    }
                }
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { viewModel.onFilePicked(it) }
            Intent.ACTION_SEND -> extractSendUri(intent)?.let { viewModel.onFilePicked(it) }
        }
    }

    private fun extractSendUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}
