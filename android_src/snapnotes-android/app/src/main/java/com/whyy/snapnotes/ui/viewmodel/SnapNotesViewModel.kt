package com.whyy.snapnotes.ui.viewmodel

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whyy.snapnotes.logic.BandStorageInfoData
import com.whyy.snapnotes.logic.AmadeusChat
import com.whyy.snapnotes.logic.FormulaPngRenderer
import com.whyy.snapnotes.logic.InterHandshake
import com.whyy.snapnotes.logic.JsonFilePusher
import com.whyy.snapnotes.logic.AmadeusDefaults
import com.whyy.snapnotes.logic.RawToLatexConverter
import com.whyy.snapnotes.notifications.ForegroundTransferService
import com.xiaomi.xms.wearable.node.Node
import com.whyy.snapnotes.ui.theme.AppearanceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.InputStream
import kotlin.time.Duration.Companion.milliseconds

/** 导入/推送文件大小上限（50MB），防止超大文件整读进内存导致 OOM。 */
const val MAX_IMPORT_FILE_BYTES = 50L * 1024 * 1024



class SnapNotesViewModel(application: Application) : AndroidViewModel(application) {

    override fun onCleared() {
        super.onCleared()
        // VM 销毁（App 真正退完）：停常驻前台服务，不留无效待命通知。释放 active 网络申请防泄漏。
        runCatching { amadeusChat?.releaseActiveNetwork() }
        runCatching { ForegroundTransferService.stopService(getApplication()) }
        // 草稿兜底落盘（防抖期内退出也能保住最新编辑）。
        if (_editorSubjects.value.isNotEmpty()) {
            runCatching { editorDraftFile.writeText(getEditorJsonString()) }
        }
    }

    private val prefs = application.getSharedPreferences("snapnotes_prefs", Context.MODE_PRIVATE)
    private val firstSyncConfirmedKey = "first_sync_confirmed"
    private val appearanceModeKey = "appearance_mode"
    private val useBuiltinFileManagerKey = "use_builtin_file_manager"
    private val lastExportDirKey = "last_export_dir"
    private val dynamicColorKey = "dynamic_color"
    private val amadeusEnabledKey = "amadeus_enabled"
    private val amadeusBaseUrlKey = "amadeus_baseurl"
    private val amadeusApiKeyKey = "amadeus_api_key"
    private val amadeusModelKey = "amadeus_model"
    private val amadeusProxyKey = "amadeus_proxy"
    private val amadeusTimeoutKey = "amadeus_timeout_sec"

    private var connection: InterHandshake? = null
    private var pusher: JsonFilePusher? = null
    private var amadeusChat: AmadeusChat? = null
    private var pendingPushUri: Uri? = null

    /* ──────────── 本地存储库 ──────────── */
    private val _localCurrentPath = MutableStateFlow("")
    val localCurrentPath = _localCurrentPath.asStateFlow()
    private val _localFolders = MutableStateFlow<List<com.whyy.snapnotes.ui.screens.LocalFolder>>(emptyList())
    val localFolders = _localFolders.asStateFlow()
    private val _localFiles = MutableStateFlow<List<com.whyy.snapnotes.ui.screens.LocalFile>>(emptyList())
    val localFiles = _localFiles.asStateFlow()

    /** 是否使用应用内文件管理器导入；关掉则回退系统文件选择器。默认开启，与参考项目一致。 */
    private val _useBuiltinFileManager =
        MutableStateFlow(prefs.getBoolean(useBuiltinFileManagerKey, true))
    val useBuiltinFileManager = _useBuiltinFileManager.asStateFlow()

    fun setUseBuiltinFileManager(enabled: Boolean) {
        prefs.edit().putBoolean(useBuiltinFileManagerKey, enabled).apply()
        _useBuiltinFileManager.value = enabled
    }

    /* ──────────── 导出（写入内置文件管理器所选本地目录） ──────────── */

    /** 最近一次导出/写入的结果提示，给 UI 展示用。 */
    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult = _exportResult.asStateFlow()

    fun dismissExportResult() {
        _exportResult.value = null
    }

    /** 上次导出所用的本地目录绝对路径；未导出过则为 null。用于在设置页展示/快速回到该目录。 */
    private val _lastExportDirSummary = MutableStateFlow<String?>(prefs.getString(lastExportDirKey, null))
    val lastExportDirSummary = _lastExportDirSummary.asStateFlow()

    /** 设置页浏览目录时确认的目录：仅记录为最近导出目录，不写文件。 */
    fun rememberExportDir(dir: java.io.File) {
        prefs.edit().putString(lastExportDirKey, dir.absolutePath).apply()
        _lastExportDirSummary.value = dir.name
    }

    /**
     * 把编辑器生成的 JSON 写入用户通过内置文件管理器选定的本地目录。
     * 同名文件已存在时会覆盖。
     * 结果通过 [exportResult] 暴露。
     */
    fun exportEditorJsonToDir(dir: java.io.File, jsonString: String, fileName: String) {
        viewModelScope.launch {
            if (!dir.exists() || !dir.isDirectory) {
                _exportResult.value = ExportResult(false, "目录不存在，请重新选择")
                return@launch
            }
            if (!dir.canWrite()) {
                _exportResult.value = ExportResult(false, "该目录不可写，请重新选择")
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val safeName = if (fileName.isBlank()) "自定义知识点.json" else fileName
                    val target = java.io.File(dir, safeName)
                    target.writeBytes(jsonString.toByteArray(Charsets.UTF_8))
                    // 记住上次导出目录，便于设置页展示与下次默认进入。
                    prefs.edit().putString(lastExportDirKey, dir.absolutePath).apply()
                    _lastExportDirSummary.value = dir.name
                    ExportResult(true, "已导出到：${dir.absolutePath}/$safeName")
                }.getOrElse { e ->
                    Log.e("SnapNotesViewModel", "export to dir fail", e)
                    ExportResult(false, e.message ?: "导出失败")
                }
            }
            _exportResult.value = result
        }
    }

    private val _appearanceMode = MutableStateFlow(
        prefs.getString(appearanceModeKey, AppearanceMode.System.name)
            ?.let { runCatching { AppearanceMode.valueOf(it) }.getOrNull() }
            ?: AppearanceMode.System
    )
    val appearanceMode = _appearanceMode.asStateFlow()

    /** 是否启用动态取色（按系统壁纸生成整套配色）；与主题模式正交，默认关闭。 */
    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(dynamicColorKey, false))
    val dynamicColor = _dynamicColor.asStateFlow()

    private val _screen = MutableStateFlow(AppScreen.Home)
    val screen = _screen.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState = _connectionState.asStateFlow()

    /* ──────────── 手环存储空间（主页圆环展示） ──────────── */
    /** null = 还没查过/刷新中无数据；非空展示。isRefreshing 单独由 loading 标记驱动，避免清空已显示数据。 */
    private val _storageInfo = MutableStateFlow<BandStorageInfoData?>(null)
    val storageInfo = _storageInfo.asStateFlow()

    /** 是否正在拉取存储空间（圆环 loading 与刷新按钮转圈）。 */
    private val _storageRefreshing = MutableStateFlow(false)
    val storageRefreshing = _storageRefreshing.asStateFlow()

    /**
     * storage_info 回包累计计数。每次 [onStorageInfo] 命中自增。
     * 用途：[refreshStorageInfo] 发查询后比对"开始时的 seq"和"延迟若干秒后的 seq"
     * 判回包是否到达，未到达则自动补发一次——兜住"首次冷拉起手环快应用时，
     * openApp().await() 返回了但手环端 onmessage 还没挂好、首包 __hs__ 被丢"
     * 这一竞态，省去用户手动点重试（参考工程靠弹窗让用户重试，这里自动化）。
     */
    private var storageInfoSeq = 0L

    /* ──────────── 连接排查页（蓝牙/已连/已装三项监控 + 全勾自动重连） ──────────── */
    private val _troubleshootState = MutableStateFlow(TroubleshootState())
    val troubleshootState = _troubleshootState.asStateFlow()
    private var troubleshootBluetoothReceiver: BroadcastReceiver? = null
    private var troubleshootPollJob: Job? = null
    private var troubleshootAutoRetryJob: Job? = null

    private val _connectionErrorState = MutableStateFlow<ConnectionErrorState?>(null)
    val connectionErrorState = _connectionErrorState.asStateFlow()

    private val _versionIncompatibleState = MutableStateFlow<VersionIncompatibleState?>(null)
    val versionIncompatibleState = _versionIncompatibleState.asStateFlow()

    private val _selectedFile = MutableStateFlow<SelectedFileState?>(null)
    val selectedFile = _selectedFile.asStateFlow()

    private val _pushState = MutableStateFlow(PushState())
    val pushState = _pushState.asStateFlow()

    /* ──────────── 公式图片推送（startFormula 链路） ──────────── */
    /** 离屏渲染器（MainActivity 注入；用 Activity context 创建 Dialog+WebView）。 */
    private var formulaRenderer: FormulaPngRenderer? = null
    /** JSON 推送成功后解析出的公式图推送计划（subject/id/formulas 列表）。 */
    private var pendingFormulaPlans: List<FormulaPlan> = emptyList()
    /** 是否正处于公式图推送阶段（JSON 已成功，正逐条推公式图）。 */
    private var formulaPhaseActive = false
    /** 公式图推送阶段累计失败条数（单条失败不整体失败，只汇总提示）。 */
    private var formulaPhaseFailCount = 0
    /** 公式图推送协程句柄（用户取消时停掉它）。 */
    private var formulaPushJob: Job? = null

    fun setFormulaRenderer(renderer: FormulaPngRenderer) {
        formulaRenderer = renderer
    }

    private val _showFirstSyncConfirm = MutableStateFlow(false)
    val showFirstSyncConfirm = _showFirstSyncConfirm.asStateFlow()

    private val _editorSubjects = MutableStateFlow<List<EditorSubject>>(emptyList())
    val editorSubjects = _editorSubjects.asStateFlow()

    private val _editorLoadError = MutableStateFlow<String?>(null)
    val editorLoadError = _editorLoadError.asStateFlow()

    private val _showDraftRestorePrompt = MutableStateFlow(false)
    val showDraftRestorePrompt = _showDraftRestorePrompt.asStateFlow()

    fun dismissEditorLoadError() {
        _editorLoadError.value = null
    }

    /* ──────────── 推送历史（仅手机端记账，不反映手环内容） ──────────── */

    private val historyFile by lazy { File(getApplication<Application>().filesDir, "snapnotes_history.json") }

    /** 编辑器草稿文件：内容自动保存，杀进程后重进编辑页可恢复。 */
    private val editorDraftFile by lazy { File(getApplication<Application>().filesDir, "snapnotes_editor_draft.json") }

    private val _pushHistory = MutableStateFlow<List<PushRecord>>(emptyList())
    val pushHistory = _pushHistory.asStateFlow()

    private val _pendingHistoryDelete = MutableStateFlow<PushRecord?>(null)
    val pendingHistoryDelete = _pendingHistoryDelete.asStateFlow()

    private val _pendingHistoryBatchDelete = MutableStateFlow<List<PushRecord>?>(null)
    val pendingHistoryBatchDelete = _pendingHistoryBatchDelete.asStateFlow()

    /** 本轮正在推送的文件名与字节，推送成功后据此落缓存并记一条历史。 */
    private var pendingPushFileName: String? = null
    private var pendingPushBytes: ByteArray? = null

    init {
        loadHistory()
        // 编辑器已合并到主页：启动时检查是否有未提交的草稿，有则提示恢复。
        _showDraftRestorePrompt.value = runCatching {
            editorDraftFile.exists() && editorDraftFile.readText().isNotBlank()
        }.getOrDefault(false)
        // 编辑器草稿自动保存：内容变化后防抖落盘（有内容才写，避免空草稿覆盖旧稿）。
        viewModelScope.launch {
            var saveJob: Job? = null
            _editorSubjects.collect { subjects ->
                if (subjects.isEmpty()) return@collect
                saveJob?.cancel()
                saveJob = viewModelScope.launch {
                    delay(800)
                    withContext(Dispatchers.IO) {
                        runCatching { editorDraftFile.writeText(getEditorJsonString()) }
                    }
                }
            }
        }
    }

    private fun loadHistory() {
        val list = mutableListOf<PushRecord>()
        runCatching {
            if (!historyFile.exists()) return@runCatching
            val arr = JSONArray(historyFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += PushRecord(
                    id = o.optString("id"),
                    fileName = o.optString("fileName"),
                    fileSize = o.optLong("fileSize", 0L),
                    pushedAt = o.optLong("pushedAt", 0L),
                    cacheFileName = o.optString("cacheFileName"),
                    subjects = o.optJSONArray("subjects")?.let { sa ->
                        List(sa.length()) { sa.optString(it) }.filter { it.isNotBlank() }
                    } ?: emptyList()
                )
            }
        }
        _pushHistory.value = list
    }

    private fun persistHistory() {
        runCatching {
            val arr = JSONArray()
            _pushHistory.value.forEach { r ->
                val o = JSONObject()
                o.put("id", r.id)
                o.put("fileName", r.fileName)
                o.put("fileSize", r.fileSize)
                o.put("pushedAt", r.pushedAt)
                o.put("cacheFileName", r.cacheFileName)
                o.put("subjects", JSONArray(r.subjects))
                arr.put(o)
            }
            historyFile.writeText(arr.toString())
        }
    }

    /** 从一份 JSON 字节里提取顶层科目名（仅作列表展示用，失败返回空）。 */
    private fun extractSubjectNames(bytes: ByteArray): List<String> {
        return runCatching {
            val root = JSONTokener(bytes.toString(Charsets.UTF_8)).nextValue()
            if (root !is JSONObject) emptyList()
            else {
                val names = mutableListOf<String>()
                root.keys().forEach { names += it }
                names
            }
        }.getOrDefault(emptyList())
    }

    /** 推送成功后调用：把字节复制进私有目录缓存，并追加一条历史记录。 */
    private fun recordPushSuccess(fileName: String, bytes: ByteArray) {
        val ts = System.currentTimeMillis()
        // 用户连续推送同名文件时仍可区分，故 id 带时间戳。
        val recordId = "p$ts"
        val cacheFileName = "snapnotes_push_$recordId.json"
        runCatching {
            File(getApplication<Application>().filesDir, cacheFileName).writeBytes(bytes)
        }
        val record = PushRecord(
            id = recordId,
            fileName = fileName.ifBlank { "knowledge.json" },
            fileSize = bytes.size.toLong(),
            pushedAt = ts,
            cacheFileName = cacheFileName,
            subjects = extractSubjectNames(bytes)
        )
        _pushHistory.update { (listOf(record) + it).take(100) }
        persistHistory()
        pendingPushBytes = null
        pendingPushFileName = null
    }

    // 调试用：显示弹窗消息
    private val _debugMessage = MutableStateFlow<String?>(null)
    val debugMessage = _debugMessage.asStateFlow()

    fun showDebug(msg: String) {
        // 调试消息只走 StateFlow 展示，不写 logcat（消息可能含笔记/公式内容）。
        _debugMessage.value = msg
    }

    fun clearDebug() {
        _debugMessage.value = null
    }

    fun requestHistoryDelete(record: PushRecord) {
        _pendingHistoryDelete.value = record
    }

    fun cancelHistoryDelete() {
        _pendingHistoryDelete.value = null
    }

    /** 删除一条历史：删本地副本文件 + 删清单。不触碰手环数据。 */
    fun confirmHistoryDelete() {
        val record = _pendingHistoryDelete.value ?: return
        _pendingHistoryDelete.value = null
        runCatching {
            val f = File(getApplication<Application>().filesDir, record.cacheFileName)
            if (f.exists()) f.delete()
        }
        _pushHistory.update { cur -> cur.filterNot { it.id == record.id } }
        persistHistory()
    }

    fun requestHistoryBatchDelete(records: List<PushRecord>) {
        _pendingHistoryBatchDelete.value = records
    }

    fun cancelHistoryBatchDelete() {
        _pendingHistoryBatchDelete.value = null
    }

    /** 批量删除历史：删对应本地副本文件 + 清清单。不触碰手环数据。 */
    fun confirmHistoryBatchDelete() {
        val records = _pendingHistoryBatchDelete.value ?: return
        _pendingHistoryBatchDelete.value = null
        if (records.isEmpty()) return
        val ids = records.map { it.id }.toSet()
        runCatching {
            records.forEach { r ->
                val f = File(getApplication<Application>().filesDir, r.cacheFileName)
                if (f.exists()) f.delete()
            }
        }
        _pushHistory.update { cur -> cur.filterNot { it.id in ids } }
        persistHistory()
    }

    /** 从历史记录重新推送（等价于把同一份 JSON 再次推给手环走 merge 链路）。 */
    fun repushRecord(record: PushRecord) {
        viewModelScope.launch {
            try {
                val cacheFile = File(getApplication<Application>().filesDir, record.cacheFileName)
                val bytes = withContext(Dispatchers.IO) {
                    if (!cacheFile.exists()) throw IllegalArgumentException("本地缓存已被删除")
                    if (cacheFile.length() > MAX_IMPORT_FILE_BYTES) {
                        throw IllegalArgumentException("缓存文件超过 50MB 上限，无法重新推送")
                    }
                    cacheFile.readBytes()
                }
                val text = bytes.toString(Charsets.UTF_8)
                // 复用已有导出推送链路（FileProvider 取 Uri → 进度页 → 手环）。
                pushFromString(text, record.fileName)
            } catch (e: Exception) {
                Log.e("SnapNotesViewModel", "repush from history fail", e)
                _pushState.update {
                    it.copy(
                        statusText = e.message ?: "重新推送失败",
                        isTransferring = false,
                        isFinished = true,
                        isSuccess = false,
                        errorMessage = e.message
                    )
                }
                _screen.value = AppScreen.Result
            }
        }
    }

    fun openHome() {
        _screen.value = AppScreen.Home
    }

    fun openSettings() {
        _screen.value = AppScreen.Settings
    }

    fun openHistory() {
        _screen.value = AppScreen.History
    }

    fun openStore() {
        // 商店页是底部导航的一部分，不需要切换 AppScreen
    }

    /**
     * 从商店导入科目知识点到编辑器并推送到手环。
     * 将商店的 StoreSubject 转为编辑器格式，同时直接生成 JSON 推送。
     */
    fun importStoreSubject(subject: com.whyy.snapnotes.data.StoreSubject) {
        viewModelScope.launch {
            val root = org.json.JSONObject()
            val arr = org.json.JSONArray()
            subject.entries.forEach { entry ->
                val obj = org.json.JSONObject()
                obj.put("id", entry.id)
                obj.put("title", entry.title)
                if (entry.desc.isNotBlank()) obj.put("desc", entry.desc)
                if (entry.raw.isNotBlank()) obj.put("raw", entry.raw)
                if (entry.points.isNotEmpty()) {
                    val pa = org.json.JSONArray()
                    entry.points.forEach { pa.put(it) }
                    obj.put("points", pa)
                }
                if (entry.formulas.isNotEmpty()) {
                    val fa = org.json.JSONArray()
                    entry.formulas.forEach { fa.put(it) }
                    obj.put("formulas", fa)
                }
                arr.put(obj)
            }
            root.put(subject.name, arr)
            pushFromString(root.toString(), "商店_${subject.name}.json")
        }
    }

    /** 批量导入多个科目（合并为单个 JSON 一次推送）。 */
    fun importStoreSubjects(subjects: List<com.whyy.snapnotes.data.StoreSubject>) {
        if (subjects.isEmpty()) {
            _snackbarMessage.value = "请至少选择一个科目"
            return
        }
        viewModelScope.launch {
            val root = org.json.JSONObject()
            subjects.forEach { subject ->
                val arr = org.json.JSONArray()
                subject.entries.forEach { entry ->
                    val obj = org.json.JSONObject()
                    obj.put("id", entry.id)
                    obj.put("title", entry.title)
                    if (entry.desc.isNotBlank()) obj.put("desc", entry.desc)
                    if (entry.raw.isNotBlank()) obj.put("raw", entry.raw)
                    if (entry.points.isNotEmpty()) {
                        val pa = org.json.JSONArray()
                        entry.points.forEach { pa.put(it) }
                        obj.put("points", pa)
                    }
                    if (entry.formulas.isNotEmpty()) {
                        val fa = org.json.JSONArray()
                        entry.formulas.forEach { fa.put(it) }
                        obj.put("formulas", fa)
                    }
                    arr.put(obj)
                }
                root.put(subject.name, arr)
            }
            pushFromString(root.toString(), "商店_批量导入.json")
        }
    }

    fun setAppearanceMode(mode: AppearanceMode) {
        _appearanceMode.value = mode
        prefs.edit().putString(appearanceModeKey, mode.name).apply()
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(dynamicColorKey, enabled).apply()
        _dynamicColor.value = enabled
    }

    /* ──────────── Amadeus（手环端 AI 聊天助手）手机端配置 ────────────
     * 见根目录「手机端AI聊天适配说明.md」第五节：key/model/baseURL/代理/超时全在手机端。
     * 这里只做 UI 可编辑 + SharedPreferences 持久化。
     * 首次启动内置 NVIDIA NIM 配置（AmadeusDefaults），开箱即可在手机端直聊中发送消息；
     * 用户在配置页覆盖后以用户值为准。
     */
    private fun loadAmadeusConfig(): AmadeusConfig = AmadeusConfig(
        enabled = prefs.getBoolean(amadeusEnabledKey, true),
        // baseUrl/apiKey/model 为空或未设置时回退到内置 NVIDIA NIM 配置：
        // 用户只要没主动填自己的值，就能开箱直接发送；填了非空值则以用户值为准。
        baseUrl = prefs.getString(amadeusBaseUrlKey, "").orEmpty()
            .takeIf { it.isNotBlank() } ?: AmadeusDefaults.BASE_URL,
        apiKey = prefs.getString(amadeusApiKeyKey, "").orEmpty()
            .takeIf { it.isNotBlank() } ?: AmadeusDefaults.API_KEY,
        model = prefs.getString(amadeusModelKey, "").orEmpty()
            .takeIf { it.isNotBlank() } ?: AmadeusDefaults.MODEL,
        proxy = prefs.getString(amadeusProxyKey, "").orEmpty(),
        timeoutSec = runCatching { prefs.getInt(amadeusTimeoutKey, 30) }.getOrDefault(30)
    )

    private val _amadeus = MutableStateFlow(loadAmadeusConfig())
    val amadeus = _amadeus.asStateFlow()

    /** 用户「启用 Amadeus」时，请求加入 Doze 电池优化白名单的一次性事件（UI 监听去弹系统授权框）。
     *  后台/锁屏跑 LLM 的前提：前台服务保进程 + 白名单放网络。两者缺一会被 Doze 掐。 */
    private val _requestBatteryOptimization = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestBatteryOptimization = _requestBatteryOptimization.asSharedFlow()

    fun setAmadeusEnabled(value: Boolean) {
        prefs.edit().putBoolean(amadeusEnabledKey, value).apply()
        _amadeus.update { it.copy(enabled = value) }
        applyAmadeusStandby()
        if (value) {
            // 启用后台聊天 ⇒ 需 Doze 白名单兜底网络，发事件让 UI 弹系统授权框。
            viewModelScope.launch { _requestBatteryOptimization.emit(Unit) }
            // 申请一条 active internet 网络句柄 + 亮屏预解析域名，给息屏 Doze 备好出站能力 + IP。
            amadeusChat?.requestActiveNetwork()
            amadeusChat?.prefetchDns(_amadeus.value)
        } else {
            amadeusChat?.releaseActiveNetwork()
        }
    }

    fun setAmadeusBaseUrl(value: String) {
        prefs.edit().putString(amadeusBaseUrlKey, value).apply()
        _amadeus.update { it.copy(baseUrl = value) }
        // 切了厂域名，趁亮屏刷新预解析缓存。
        amadeusChat?.prefetchDns(_amadeus.value)
    }

    fun setAmadeusApiKey(value: String) {
        prefs.edit().putString(amadeusApiKeyKey, value).apply()
        _amadeus.update { it.copy(apiKey = value) }
    }

    fun setAmadeusModel(value: String) {
        prefs.edit().putString(amadeusModelKey, value).apply()
        _amadeus.update { it.copy(model = value) }
    }

    fun setAmadeusProxy(value: String) {
        prefs.edit().putString(amadeusProxyKey, value).apply()
        _amadeus.update { it.copy(proxy = value) }
    }

    fun setAmadeusTimeout(seconds: Int) {
        prefs.edit().putInt(amadeusTimeoutKey, seconds).apply()
        _amadeus.update { it.copy(timeoutSec = seconds) }
    }

    /* ──────────── 重置首次同步确认 ──────────── */

    /** 把首次同步确认写回 false，下次推送重新弹倒计时确认弹窗。 */
    fun resetFirstSyncConfirm() {
        prefs.edit().putBoolean(firstSyncConfirmedKey, false).apply()
        _snackbarMessage.value = "已重置首次同步确认"
    }

    fun setConnection(conn: InterHandshake) {
        // 用 E 级别埋探针：某些国产 ROM 会静默 D 级别日志，导致 Log.d 一条不显示，排查无从下手。
        Log.e("SnapNotesViewModel", "PROBE setConnection called; sameAsExisting=${connection === conn}")
        if (connection === conn) {
            Log.e("SnapNotesViewModel", "PROBE setConnection: same conn, reconnect SKIPPED")
            return
        }
        connection = conn
        pusher = JsonFilePusher(conn).apply {
            onProgress = { progress, preview, status ->
                _pushState.update {
                    it.copy(
                        progress = progress.coerceIn(0.0, 1.0),
                        preview = preview,
                        statusText = status,
                        isTransferring = true,
                        isFinished = false
                    )
                }
            }
            onSuccess = { message ->
                // 公式图推送阶段：单条公式图完成的回执，由外层循环统一收尾，这里不处理。
                if (!formulaPhaseActive) {
                    _pushState.update {
                        it.copy(
                            progress = 1.0,
                            statusText = message,
                            isTransferring = false,
                            isFinished = true,
                            isSuccess = true,
                            errorMessage = null
                        )
                    }
                    // 推送成功：记一条历史（手机端记账；手环合并规则同 id 不覆盖，删除本记录不删手环数据）。
                    pendingPushFileName?.let { name -> pendingPushBytes?.let { bytes ->
                        recordPushSuccess(name, bytes)
                        // JSON 已同步：若该文件含公式条目，进入公式图推送阶段；否则直接收尾。
                        // 必须把 bytes 传进去——recordPushSuccess 会清空 pendingPushBytes。
                        launchFormulaPushIfAny(bytes)
                    } }
                }
            }
            onError = { message ->
                // 公式图推送阶段：单条失败不整体失败，累计后继续推下一条，收尾时汇总提示。
                if (formulaPhaseActive) {
                    formulaPhaseFailCount++
                    Log.e("SnapNotesViewModel", "formula push fail: $message")
                    _pushState.update {
                        it.copy(
                            statusText = "公式图推送失败，继续下一条",
                            isTransferring = false,
                            isFinished = false,
                            isSuccess = false,
                            errorMessage = message
                        )
                    }
                } else {
                    _pushState.update {
                        it.copy(
                            statusText = message,
                            isTransferring = false,
                            isFinished = true,
                            isSuccess = false,
                            errorMessage = message
                        )
                    }
                    _screen.value = AppScreen.Result
                }
            }
            // 存储空间查询回包：异步落 StateFlow 供主页圆环展示。命中即自增 seq 让 refreshStorageInfo
            // 能判"回包是否在本轮到达"，未到达时自动补发一次兜首次冷拉起丢包竞态。
            onStorageInfo = { data -> _storageInfo.value = data; storageInfoSeq++ }
        }

        conn.setOnVersionIncompatible { currentVersion, requiredVersion, requiredVersionName ->
            _versionIncompatibleState.value = VersionIncompatibleState(
                currentVersion = currentVersion,
                requiredVersion = requiredVersion,
                requiredVersionName = requiredVersionName
            )
        }
        conn.addOnDisconnectedListener {
            markConnectionFailed("连接已断开")
            // 断开后旧存储数据已无意义，清掉避免圆环显示陈旧值。
            _storageInfo.value = null
            // 断开后待命无意义：停前台服务避免空耗 + 无效通知。释放 active 网络申请防泄漏。
            amadeusChat?.releaseActiveNetwork()
            if (!_pushState.value.isTransferring) {
                ForegroundTransferService.stopService(getApplication())
            }
            scheduleAutoReconnect()
        }

        // 启动即自动检测手环连接，主页实时反映状态。
        reconnect()

        // 接入 Amadeus 聊天通道：注册 __chat__ 入站分发，读实时 amadeus 配置喂入。
        // 复用 pusher 的共享锁与单 BLE 通道串行下发；onDisconnectedListener 自带上下文清空。
        // 传 application context：AmadeusChat 用它申请 active 网络句柄，绕过息屏 Doze 的出站能力降级。
        amadeusChat = AmadeusChat(getApplication(), conn, pusher!!, _amadeus, viewModelScope)
        // 把 AmadeusChat.lastCall 透传给 UI（上下文页回显最近一次调用状态）。
        viewModelScope.launch { amadeusChat!!.lastCall.collect { _amadeusLastCall.value = it } }
        // 把 AmadeusChat.phoneChatStatus 透传给 UI（手机端直聊页面观测加载/成功/失败）。
        viewModelScope.launch { amadeusChat!!.phoneChatStatus.collect { _phoneChatStatus.value = it } }
    }

    /* ──────────── Amadeus 上下文管理 / 最近调用状态（联「上下文管理菜单」） ──────────── */
    private val _amadeusLastCall = MutableStateFlow<AmadeusChat.CallStatus>(AmadeusChat.CallStatus.Idle)
    val amadeusLastCall = _amadeusLastCall.asStateFlow()

    /** 会话快照列表（每次 UI 进入上下文页拉一次即可；列表由用户操作驱动，无需 StateFlow）。 */
    fun amadeusSnapshots(): List<AmadeusChat.SessionSnapshot> = amadeusChat?.snapshots() ?: emptyList()

    fun amadeusDetail(id: String): AmadeusChat.SessionDetail? = amadeusChat?.detail(id)

    fun clearAmadeusSession(id: String) { amadeusChat?.clearSession(id) }

    fun clearAllAmadeus() { amadeusChat?.clearAll() }

    /** 本地测试发送：手机端跑完整 SSE，不发 BLE。供「上下文管理菜单 · 测试发送」。 */
    fun testSendAmadeus(text: String) {
        if (text.isBlank()) return
        amadeusChat?.testSend(text)
    }

    /** 最近一次回复的完整 assistant 文本：lastCall 关联会话的最后一条 assistant 消息。 */
    fun exportLastAmadeusReply(): String? {
        val status = _amadeusLastCall.value
        val sid = (status as? AmadeusChat.CallStatus.Success)?.sid
            ?: (status as? AmadeusChat.CallStatus.Failed)?.sid
            ?: return null
        val detail = amadeusChat?.detail(sid) ?: return null
        return detail.messages.lastOrNull { it.first == "assistant" }?.second
    }

    /* ──────────── 手机端 Amadeus 直聊（不走 BLE，用于生成 JSON） ──────────── */
    private val _phoneChatMessages = MutableStateFlow<List<AmadeusChat.PhoneChatMessage>>(emptyList())
    val phoneChatMessages = _phoneChatMessages.asStateFlow()

    private val _phoneChatStatus = MutableStateFlow<AmadeusChat.PhoneChatStatus>(AmadeusChat.PhoneChatStatus.Idle)
    val phoneChatStatus = _phoneChatStatus.asStateFlow()

    /** 发送手机端聊天消息。fileContent 非空时会作为上下文附给 AI。 */
    fun sendPhoneChatMessage(text: String, fileContent: String? = null) {
        if (text.isBlank() && fileContent.isNullOrBlank()) return
        viewModelScope.launch {
            amadeusChat?.sendPhoneChat(text, fileContent)
            _phoneChatMessages.value = amadeusChat?.getPhoneChatHistory() ?: emptyList()
        }
    }

    /** 清空手机端聊天历史。 */
    fun clearPhoneChat() {
        amadeusChat?.clearPhoneChat()
        _phoneChatMessages.value = emptyList()
    }

    /** 获取当前聊天历史快照（进入页面时初始化用）。 */
    fun getPhoneChatHistory(): List<AmadeusChat.PhoneChatMessage> =
        amadeusChat?.getPhoneChatHistory() ?: emptyList()

    /** 将 AI 回复的 JSON 导入到手环（走推送流程）。 */
    fun importJsonFromChat(jsonString: String) {
        val cleanJson = extractJsonObject(jsonString)
        if (cleanJson == null) {
            showSnackbar("未在 AI 回复中识别到有效的 JSON 对象")
            return
        }
        pushFromString(cleanJson, "AI生成_${System.currentTimeMillis()}.json")
    }

    /**
     * 从 AI 回复文本中提取 JSON 对象：去掉可能的 markdown 代码块围栏后，
     * 截取第一个 `{` 到最后一个 `}` 之间的内容，并校验它是单一合法的 JSON 对象。
     * 提取失败返回 null（供调用方提示用户）。
     */
    private fun extractJsonObject(text: String): String? {
        var s = text.trim()
        s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        s = s.removeSuffix("```").trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = s.substring(start, end + 1)
        return runCatching {
            val tok = JSONTokener(candidate)
            val root = tok.nextValue()
            if (root !is JSONObject) return null
            if (tok.more()) return null   // 还有多余内容，说明不是单一完整对象
            candidate
        }.getOrNull()
    }

    private var autoRetryJob: Job? = null

    private fun scheduleAutoReconnect() {
        autoRetryJob?.cancel()
        autoRetryJob = viewModelScope.launch {
            delay(5_000L)
            if (_connectionState.value.isConnected) return@launch
            _connectionState.update {
                it.copy(statusText = "正在尝试重连", descriptionText = "5 秒后自动重连…")
            }
            reconnect()
        }
    }

    /** 用户手动重试连接（排查页三项全过后触发 / 其它手动入口）。 */
    fun retryConnection() {
        autoRetryJob?.cancel()
        reconnect()
    }

    private fun shouldAmadeusStandby(): Boolean =
        _amadeus.value.enabled && _connectionState.value.isConnected

    /**
     * 按 [shouldAmadeusStandby] 起停「Amadeus 待命」前台服务。
     * - 该待命：起 stand­by 通知（"Amadeus 待命 · 已连接 {device}"）；传输中时此调用被传输观察者接管，
     *   但 onStartCommand 的 MODE_STANDBY 在 onCreate/storeForeground 后会 setStandbyNotification，
     *   传输一旦结束 MainActivity 的观察者会再 startService(传输态)→但这里也再 startStandby，
     *   二者同 id 互斥，最终态由最后一次调用决定，靠观察者顺序保证传输结束后切回待命。
     * - 不该待命（未启用 / 断开 / 未安装 / 不支持）：停服务。
     *
     * 注意：传输进行中（_pushState.isTransferring）时不停止服务——交给传输观察者管传输态，
     * 避免传输中途停前台服务导致进度通知闪退。仅当不传输且不该待命才 stopService。
     */
    private fun applyAmadeusStandby() {
        val ctx = getApplication<Application>()
        if (shouldAmadeusStandby()) {
            val device = _connectionState.value.deviceName
            ForegroundTransferService.startStandby(
                ctx,
                "Amadeus 待命",
                device?.let { "已连接 $it" } ?: "已连接"
            )
        } else if (!_pushState.value.isTransferring) {
            ForegroundTransferService.stopService(ctx)
        }
    }

    /**
     * 供 MainActivity 传输态观察者在「传输结束」时调用：
     * 若 Amadeus 仍启用且已连，切回待命通知；否则停服务。
     * 传输中不要调（传输态自己管通知）。
     */
    fun applyForegroundServiceAfterTransfer() {
        if (_pushState.value.isTransferring) return
        applyAmadeusStandby()
    }

    /**
     * 拉取一次手环存储空间。连接成功后自动调用，主页刷新按钮也走这里。
     *
     * 与参考工程 `com.bandbbs.ebook-android/MainViewModel.refreshBandStorageInfo` 完全对齐：
     * - [com.whyy.snapnotes.logic.JsonFilePusher.getStorageInfo] 是 fire-and-forget，只 await「发送完成」；
     * - 回包由 [com.whyy.snapnotes.logic.JsonFilePusher.onStorageInfo] 回调异步上送，
     *   回调里直接落 [_storageInfo]，**不依赖 getStorageInfo 的返回值**；
     * - 这样回包即使晚于本函数返回也能刷上 StateFlow，避免「发后等回包」超时丢包的竞态。
     */
    fun refreshStorageInfo() {
        val pusher = pusher ?: run {
            Log.d("SnapNotesViewModel", "refreshStorageInfo: pusher 未初始化")
            return
        }
        if (!_connectionState.value.isConnected) {
            Log.d("SnapNotesViewModel", "refreshStorageInfo: 未连接，跳过")
            return
        }
        // 记下本轮开始的回包 seq，用于补发前判「本轮是否已收到回包」。
        val seqBefore = storageInfoSeq
        viewModelScope.launch {
            _storageRefreshing.value = true
            try {
                // 只等「发送完成」；回包异步经 onStorageInfo 落 _storageInfo。
                // 首次冷拉起手环快应用时，openApp().await() 返回后手环端 onmessage 可能还没挂好，
                // 首包 __hs__ 被丢 → sendMessage 内部 ensureHandshake 卡 8s 超时。这里 try/catch 吞掉，
                // 不让单次 send 失败影响下面的补发逻辑。
                withTimeout(8_000L.milliseconds) {
                    pusher.getStorageInfo()
                }
            } catch (e: Exception) {
                Log.e("SnapNotesViewModel", "refreshStorageInfo send fail: ${e.message}")
            }

            // 自动补发：等 3.5s 看回包是否到（onStorageInfo 命中会自增 storageInfoSeq）。
            // 手环端首次冷拉起到能稳定收消息约 1~3s，3.5s 仍未到说明首包丢了，需补一次。
            // 到了就不补（避免无谓并发）。这是参考工程弹窗让用户点重试的自动版。
            delay(3_500L)
            if (storageInfoSeq == seqBefore && _connectionState.value.isConnected) {
                Log.d("SnapNotesViewModel", "refreshStorageInfo: 3.5s 内无回包，复位握手并补发一次")
                // 首包丢了之后 InterHandshake 的 handshakePromise 会一直 pending 到 10s 才解锁，
                // 这之前任何 sendMessage 都卡在 isHandshaking 分支 await 同一个死 promise、补不出去。
                // 这里主动 resetHandshake 清掉死 promise，让补发的 sendMessage 重新发一次 __hs__。
                connection?.resetHandshake()
                try {
                    withTimeout(8_000L.milliseconds) {
                        pusher.getStorageInfo()
                    }
                } catch (e: Exception) {
                    Log.e("SnapNotesViewModel", "refreshStorageInfo resend fail: ${e.message}")
                }
            }

            // loading 由「补发链路结束」收尾。回包即便在此之后到，回调仍会刷 _storageInfo。
            _storageRefreshing.value = false
        }
    }

    /* ──────────── 连接排查页：三项监控 + 全勾自动重连 ────────────
     * 排查项分两类：
     *   ① 蓝牙——用系统广播 ACTION_STATE_CHANGED 实时推（开/关即时变绿/红）；
     *   ② 已连设备 / 已装应用——nodeApi 轮询（约 3s 一次），排查页存活期间持续轮询。
     * 第 ② 项里的「已装」依赖「已连设备」：设备没查到（nodes 空）时把已装置 NotApplicable（灰态问号）。
     */

    /**
     * 进入排查页时调：复位状态、注册蓝牙广播、起轮询协程、起自动重连收集器。
     */
    fun startTroubleshoot() {
        // 复位为初始 Checking 态（autoRetrying 也清零，避免上一轮残留）。
        _troubleshootState.value = TroubleshootState()

        // ── 蓝牙：注册系统广播监听开关变化 ──
        registerBluetoothReceiver()
        // 先同步查一次当前蓝牙状态（兜住「进页面时蓝牙已开/已关」不需要等广播）。
        probeBluetoothOnce()

        // ── 已连设备 + 已装应用：3s 轮询 ──
        troubleshootPollJob?.cancel()
        troubleshootPollJob = viewModelScope.launch {
            while (isActive) {
                pollNodeAndInstall()
                delay(3_000L)
            }
        }

        // ── 全勾自动重连：监听 allVerifiablePassed 翻转 ──
        troubleshootAutoRetryJob?.cancel()
        troubleshootAutoRetryJob = viewModelScope.launch {
            _troubleshootState
                .map { it.allVerifiablePassed to it.autoRetrying }
                .distinctUntilChanged()
                .collect { (allPassed, alreadyRetrying) ->
                    if (allPassed) {
                        // 三项全过：只在未在重连时触发一次；已在重连则不重复发。
                        if (!alreadyRetrying) {
                            _troubleshootState.update { it.copy(autoRetrying = true) }
                            retryConnection()
                        }
                    } else {
                        // 任一项又变未过（重连中途链路掉了等）：复位 autoRetrying，等下次重新全过再触发。
                        if (alreadyRetrying) {
                            _troubleshootState.update { it.copy(autoRetrying = false) }
                        }
                    }
                }
        }
    }

    /**
     * 离开排查页时调：解注册广播、停轮询与自动重连收集器、清 autoRetrying 标志。
     */
    fun stopTroubleshoot() {
        unregisterBluetoothReceiver()
        troubleshootPollJob?.cancel()
        troubleshootPollJob = null
        troubleshootAutoRetryJob?.cancel()
        troubleshootAutoRetryJob = null
        _troubleshootState.update { it.copy(autoRetrying = false) }
    }

    /**
     * 用户对 BLUETOOTH_CONNECT 的授权返回（runtime 申请回调）。授权后立即按当前蓝牙状态Probe一次。
     * 拒绝时蓝牙项仍 Checking（State 已标 bluetoothPermissionGranted=false，页面会持续提示需授权）。
     */
    fun setTroubleshootBluetoothPermissionGranted(granted: Boolean) {
        _troubleshootState.update { it.copy(bluetoothPermissionGranted = granted) }
        if (granted) probeBluetoothOnce()
    }

    /** 自动重连成功后供 UI 调，清理 autoRetrying 标志（避免下次进页面残留）。 */
    fun onAutoRetrySucceeded() {
        _troubleshootState.update { it.copy(autoRetrying = false) }
    }

    // ── 蓝牙 ──

    private fun hasBluetoothConnectPermission(): Boolean {
        // Android 12+ 才需 BLUETOOTH_CONNECT 才能读 isEnabled / 收广播；低于 12 直接放行。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 同步判一次当前蓝牙是否开启（在已授权的前提下）。未授权时记 Checking 并置权限未授予标志。 */
    private fun probeBluetoothOnce() {
        if (!hasBluetoothConnectPermission()) {
            _troubleshootState.update {
                it.copy(bluetooth = CheckResult.Checking, bluetoothPermissionGranted = false)
            }
            return
        }
        _troubleshootState.update { it.copy(bluetoothPermissionGranted = true) }
        val enabled = runCatching {
            val mgr = getApplication<Application>().getSystemService(BluetoothManager::class.java)
            mgr?.adapter?.isEnabled == true
        }.getOrDefault(false)
        _troubleshootState.update {
            it.copy(bluetooth = if (enabled) CheckResult.Pass else CheckResult.Fail)
        }
    }

    private fun registerBluetoothReceiver() {
        unregisterBluetoothReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR
                )
                _troubleshootState.update {
                    it.copy(bluetooth = if (state == BluetoothAdapter.STATE_ON) CheckResult.Pass else CheckResult.Fail)
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        // Android 14+ 要求显式 RECEIVER_NOT_EXPORTED（系统广播，非导出即可）。
        ContextCompat.registerReceiver(
            getApplication(), receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        troubleshootBluetoothReceiver = receiver
    }

    private fun unregisterBluetoothReceiver() {
        val r = troubleshootBluetoothReceiver ?: return
        runCatching { getApplication<Application>().unregisterReceiver(r) }
        troubleshootBluetoothReceiver = null
    }

    // ── 已连设备 + 已装应用：轮询 ──

    /**
     * 查一次 connectedNodes；非空 → 设备项 Pass，再查 isWearAppInstalled；空/fail → 设备项 Fail、已装 NotApplicable。
     */
    private suspend fun pollNodeAndInstall() {
        val conn = connection ?: return
        val nodes = suspendCancellableCoroutine<List<Node>?> { cont ->
            conn.nodeApi.connectedNodes
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }
        if (nodes.isNullOrEmpty()) {
            _troubleshootState.update {
                it.copy(
                    deviceConnected = CheckResult.Fail,
                    appInstalled = CheckResult.NotApplicable
                )
            }
            return
        }
        _troubleshootState.update { it.copy(deviceConnected = CheckResult.Pass) }

        // 用第一个已连节点查安装态（与主页 connect() 取 nodes[0] 对齐）。
        val installed = suspendCancellableCoroutine<Boolean> { cont ->
            conn.nodeApi.isWearAppInstalled(nodes[0].id)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(false) }
        }
        _troubleshootState.update {
            it.copy(appInstalled = if (installed) CheckResult.Pass else CheckResult.Fail)
        }
    }

    /* ──────────── 编辑器 ──────────── */

    fun openEditor() {
        // 编辑页无内容且存在草稿时，提示恢复上次未提交的编辑。
        if (_editorSubjects.value.isEmpty()) {
            _showDraftRestorePrompt.value = runCatching {
                editorDraftFile.exists() && editorDraftFile.readText().isNotBlank()
            }.getOrDefault(false)
        }
        _screen.value = AppScreen.Editor
    }

    /** 恢复舞台草稿：解析草稿文件 → 载入编辑器 → 关闭提示。 */
    fun restoreEditorDraft() {
        _showDraftRestorePrompt.value = false
        viewModelScope.launch {
            val subjects = withContext(Dispatchers.IO) {
                runCatching { parseEditorSubjects(editorDraftFile.readText()) }.getOrNull()
            }
            if (subjects == null) {
                _editorLoadError.value = "草稿文件已损坏，无法恢复"
                return@launch
            }
            _editorSubjects.value = subjects
        }
    }

    /** 丢弃草稿：删除草稿文件并关闭提示。 */
    fun discardEditorDraft() {
        _showDraftRestorePrompt.value = false
        runCatching { editorDraftFile.delete() }
    }

    fun openEditorFromFile(uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { readAndValidateJson(uri) }
                val text = bytes.toString(Charsets.UTF_8)
                val subjects = parseEditorSubjects(text)
                _editorSubjects.value = subjects
                _editorLoadError.value = null
                _snackbarMessage.value = "已加载 ${subjects.size} 个科目到知识点管理"
                _screen.value = AppScreen.Editor
            } catch (e: Exception) {
                Log.e("SnapNotesViewModel", "open editor from file fail", e)
                _editorLoadError.value = e.message ?: "无法加载该 JSON 文件"
            }
        }
    }

    /** 把「知识点 JSON 文本」解析为编辑器科目列表。与 getEditorJsonString() 互逆。 */
    private fun parseEditorSubjects(text: String): List<EditorSubject> {
        val root = org.json.JSONObject(text)
        val subjects = mutableListOf<EditorSubject>()
        root.keys().forEach { subjectName ->
            val arr = root.getJSONArray(subjectName)
            val entries = mutableListOf<EditorEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                entries += EditorEntry(
                    id = obj.optString("id", ""),
                    title = obj.optString("title", ""),
                    desc = obj.optString("desc", ""),
                    raw = obj.optString("raw", ""),
                    points = obj.optJSONArray("points")?.let { pArr ->
                        List(pArr.length()) { pArr.optString(it, "") }
                    } ?: emptyList(),
                    formulas = obj.optJSONArray("formulas")?.let { fArr ->
                        List(fArr.length()) { fArr.optString(it, "") }
                    } ?: emptyList()
                )
            }
            subjects += EditorSubject(name = subjectName, entries = entries)
        }
        return subjects
    }

    /**
     * 从历史缓存文件加载到编辑器
     */
    fun openEditorFromCache(record: PushRecord) {
        viewModelScope.launch {
            val cacheFile = File(getApplication<Application>().filesDir, record.cacheFileName)
            if (!cacheFile.exists()) {
                _editorLoadError.value = "缓存文件已不存在"
                return@launch
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                getApplication(),
                "${getApplication<Application>().packageName}.fileprovider",
                cacheFile
            )
            openEditorFromFile(uri)   // 复用已有加载逻辑
        }
    }

    /** 从商店知识点导入到编辑器（不推送，仅加载到编辑器供用户编辑）。 */
    fun loadEditorFromStoreSubject(subject: com.whyy.snapnotes.data.StoreSubject) {
        val editorEntries = subject.entries.map { entry ->
            EditorEntry(
                id = entry.id.toString(),
                title = entry.title,
                desc = entry.desc,
                raw = entry.raw,
                points = entry.points,
                formulas = entry.formulas
            )
        }
        _editorSubjects.value = listOf(EditorSubject(name = subject.name, entries = editorEntries))
        _screen.value = AppScreen.Editor
    }

    fun addSubject() {
        _editorSubjects.update { it + EditorSubject(name = "新科目", entries = emptyList()) }
    }

    fun removeSubject(index: Int) {
        _editorSubjects.update { it.toMutableList().also { list -> list.removeAt(index) } }
    }

    fun updateSubjectName(index: Int, newName: String) {
        _editorSubjects.update { it.toMutableList().also { list -> list[index] = list[index].copy(name = newName) } }
    }

    fun addEntry(subjectIndex: Int) {
        _editorSubjects.update {
            it.toMutableList().also { list ->
                val subject = list[subjectIndex]
                list[subjectIndex] = subject.copy(
                    entries = subject.entries + EditorEntry(title = "新条目")
                )
            }
        }
    }

    fun removeEntry(subjectIndex: Int, entryIndex: Int) {
        _editorSubjects.update {
            it.toMutableList().also { list ->
                val subject = list[subjectIndex]
                list[subjectIndex] = subject.copy(
                    entries = subject.entries.toMutableList().also { it.removeAt(entryIndex) }
                )
            }
        }
    }

    fun updateEntry(subjectIndex: Int, entryIndex: Int, newEntry: EditorEntry) {
        _editorSubjects.update {
            it.toMutableList().also { list ->
                val subject = list[subjectIndex]
                list[subjectIndex] = subject.copy(
                    entries = subject.entries.toMutableList().also { it[entryIndex] = newEntry }
                )
            }
        }
    }

    fun getEditorJsonString(): String {
        val root = org.json.JSONObject()
        _editorSubjects.value.forEach { subject ->
            val arr = org.json.JSONArray()
            subject.entries.forEach { entry ->
                val obj = org.json.JSONObject()
                if (entry.id.isNotBlank()) obj.put("id", entry.id.toIntOrNull() ?: entry.id)
                obj.put("title", entry.title)
                if (entry.desc.isNotBlank()) obj.put("desc", entry.desc)
                if (entry.raw.isNotBlank()) obj.put("raw", entry.raw)
                if (entry.points.isNotEmpty()) obj.put("points", org.json.JSONArray(entry.points))
                if (entry.formulas.isNotEmpty()) obj.put("formulas", org.json.JSONArray(entry.formulas))
                arr.put(obj)
            }
            root.put(subject.name, arr)
        }
        return root.toString(2)
    }

    /** 把编辑器当前内容生成 JSON 文件写入缓存目录，再走已有推送链路。 */
    fun pushFromString(jsonString: String, fileName: String) {
        viewModelScope.launch {
            try {
                val bytes = jsonString.toByteArray(Charsets.UTF_8)
                val file = java.io.File(getApplication<Application>().cacheDir, fileName)
                file.writeBytes(bytes)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    getApplication(),
                    "${getApplication<Application>().packageName}.fileprovider",
                    file
                )
                // 复用已有推送入口（含首次确认 / 校验 / 进度页）。
                pendingPushUri = uri
                _selectedFile.value = SelectedFileState(uri, fileName, bytes.size.toLong())
                _pushState.value = PushState(fileName = fileName, fileSize = bytes.size.toLong(), statusText = "等待中")
                startPushFromUri(uri)
            } catch (e: Exception) {
                Log.e("SnapNotesViewModel", "push from editor fail", e)
                _pushState.update {
                    it.copy(
                        statusText = e.message ?: "导出失败",
                        isTransferring = false,
                        isFinished = true,
                        isSuccess = false,
                        errorMessage = e.message
                    )
                }
                _screen.value = AppScreen.Result
            }
        }
    }

    /**
     * 从本地存储库的文件直接发起推送（跳过"选择文件"步骤，直接进入文件夹选择 + 推送流程）。
     *
     * 与 [pushFromString] 类似，但输入是已存在的本地 [File]，不需要写缓存。
     * 用于本地存储库页面"导入手环"按钮。
     */
    fun pushFromFile(file: java.io.File) {
        val uri = Uri.fromFile(file)
        _selectedFile.value = SelectedFileState(uri, file.name, file.length())
        _pushState.value = PushState(
            fileName = file.name,
            fileSize = file.length(),
            statusText = "等待中"
        )
        startPushFromUri(uri)
    }

    /**
     * 立即发起一次连接链路：destroy → connect → auth → getAppState → openApp → registerListener → init。
     * 全程更新 _connectionState；失败/超时/未装/不支持都落到 ConnectionErrorState。
     */
    fun reconnect() {
        val conn = connection ?: return
        Log.e("SnapNotesViewModel", "PROBE reconnect start")
        viewModelScope.launch {
            _connectionState.update {
                it.copy(
                    statusText = "连接中",
                    descriptionText = "请确保小米运动健康后台运行",
                    isConnected = false,
                    deviceName = null
                )
            }
            try {
                withTimeout(8_000L.milliseconds) {
                    conn.destroy().await()
                    Log.e("SnapNotesViewModel", "PROBE reconnect destroyed")
                    val deviceName = conn.connect().await()
                    Log.e("SnapNotesViewModel", "PROBE reconnect connectedNodes ok: $deviceName")

                    val unsupportedDevices = listOf("小米手环8")
                    if (unsupportedDevices.any { it == deviceName }) {
                        _connectionState.update {
                            it.copy(
                                statusText = "不受支持",
                                descriptionText = "$deviceName 暂不支持",
                                isConnected = false,
                                deviceName = deviceName
                            )
                        }
                        _connectionErrorState.value = ConnectionErrorState(
                            deviceName = deviceName,
                            isUnsupportedDevice = true,
                            message = "$deviceName 暂不支持闪念小抄同步"
                        )
                        return@withTimeout
                    }

                    _connectionState.update {
                        it.copy(
                            statusText = "授权中",
                            descriptionText = "$deviceName 正在授权",
                            isConnected = false,
                            deviceName = deviceName
                        )
                    }
                    conn.auth().await()
                    Log.e("SnapNotesViewModel", "PROBE reconnect auth ok")

                    val installed = conn.getAppState().await()
                    Log.e("SnapNotesViewModel", "PROBE reconnect isWearAppInstalled=$installed")
                    if (!installed) {
                        _connectionState.update {
                            it.copy(
                                statusText = "未安装",
                                descriptionText = "请先在手环上安装闪念小抄快应用",
                                isConnected = false,
                                deviceName = deviceName
                            )
                        }
                        _connectionErrorState.value = ConnectionErrorState(
                            deviceName = deviceName,
                            message = "请先在手环上安装闪念小抄快应用"
                        )
                        return@withTimeout
                    }

                    _connectionState.update {
                        it.copy(
                            statusText = "拉起手环端",
                            descriptionText = "$deviceName 正在打开导入页",
                            isConnected = false,
                            deviceName = deviceName
                        )
                    }
                    conn.openApp().await()
                    Log.e("SnapNotesViewModel", "PROBE reconnect openApp ok $deviceName")
                    conn.registerListener().await()
                    Log.e("SnapNotesViewModel", "PROBE reconnect registerListener ok")
                    // 不在此 await conn.init()：握手在 InterHandshake 构造时已异步发起，
                    // 这里强行同步等握手完成会把「等手环快应用冷启动」塞进本 withTimeout(8s)
                    // 超时块——首次冷拉起 >8s 时直接报「连接超时」、圆环无数据，删后台重进才正常。
                    // 对齐参考工程 ConnectionHandler.reconnect：openApp→registerListener 后直接置
                    // isConnected=true 触发查询；握手最终由 sendMessage→ensureHandshake 异步兜。
                    _connectionState.update {
                        it.copy(
                            statusText = "连接成功",
                            descriptionText = "$deviceName 已连接",
                            isConnected = true,
                            deviceName = deviceName
                        )
                    }
                    // 连接成功后立即查一次存储空间供主页圆环展示。
                    // 与参考工程 MainViewModel.onBandConnected 对齐：reconnect 协程末尾 isConnected=true 时
                    // 手环 file 应用已 await 就绪，无需额外延迟。
                    refreshStorageInfo()
                    applyAmadeusStandby()
                    // 连接成功 = 即将进入后台待命，趁亮屏立刻申请 active 网络 + 预解析域名入缓存。
                    amadeusChat?.requestActiveNetwork()
                    amadeusChat?.prefetchDns(_amadeus.value)
                    Log.e("SnapNotesViewModel", "PROBE reconnect done")
                }
            } catch (_: TimeoutCancellationException) {
                Log.e("SnapNotesViewModel", "reconnect: TIMEOUT (8s)")
                markConnectionFailed("连接超时")
                scheduleAutoReconnect()
            } catch (e: Exception) {
                Log.e("SnapNotesViewModel", "connect fail ${e.message}")
                markConnectionFailed(e.message ?: "连接失败")
                scheduleAutoReconnect()
            }
        }
    }

    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { getFileInfo(uri) }
            _selectedFile.value = info
            _pushState.value = PushState(fileName = info.fileName, fileSize = info.fileSize)
            _screen.value = AppScreen.Home
        }
    }

    /** 内置文件管理器选中本地文件后：转成 Uri 走既有「主页选文件」链路。 */
    fun onBuiltinFilePicked(file: java.io.File) {
        onFilePicked(Uri.fromFile(file))
    }

    /** 内置文件管理器在编辑器入口选中本地文件后：加载进编辑器。 */
    fun onBuiltinFilePickedForEditor(file: java.io.File) {
        openEditorFromFile(Uri.fromFile(file))
    }

    fun startPushFromSelected() {
        val file = _selectedFile.value ?: return
        startPushFromUri(file.uri)
    }

    fun startPushFromUri(uri: Uri) {
        pendingPushUri = uri
        if (!prefs.getBoolean(firstSyncConfirmedKey, false)) {
            _showFirstSyncConfirm.value = true
            return
        }
        // 直接推送到手环根目录（不再经过文件夹选择流程）
        viewModelScope.launch { doPush(uri, null) }
    }

    fun confirmFirstSync() {
        prefs.edit().putBoolean(firstSyncConfirmedKey, true).apply()
        _showFirstSyncConfirm.value = false
        // 首次确认后直接推送
        pendingPushUri?.let { uri ->
            viewModelScope.launch { doPush(uri, null) }
        }
    }

    fun cancelFirstSyncConfirm() {
        _showFirstSyncConfirm.value = false
        pendingPushUri = null
    }

    fun dismissConnectionError() {
        _connectionErrorState.value = null
    }

    fun dismissVersionIncompatible() {
        _versionIncompatibleState.value = null
    }

    fun cancelPush() {
        formulaPushJob?.cancel()
        formulaPushJob = null
        formulaPhaseActive = false
        pendingFormulaPlans = emptyList()
        pusher?.cancel()
        _pushState.update {
            it.copy(
                statusText = "已取消",
                isTransferring = false,
                isFinished = true,
                isSuccess = false,
                errorMessage = "用户取消"
            )
        }
        _screen.value = AppScreen.Home
    }

    fun backHome() {
        _screen.value = AppScreen.Home
    }

    fun retry() {
        pendingPushUri?.let { startPushFromUri(it) }
    }

    /**
     * JSON 推送成功后的公式图串联入口：从 [pushBytes]（本次已成功推送的 JSON 字节）解析含 formulas 的条目，
     * 逐条渲染 PNG 并用 [JsonFilePusher.pushFormula] 推给手环；全部完成后统一切结果页。
     * 单条公式渲染/推送失败只累计计数，不中断整体；无公式条目则直接切结果页。
     *
     * 注意：不能依赖 [pendingPushBytes]——它会在 [recordPushSuccess] 中被清空，必须把字节传进来。
     */
    private fun launchFormulaPushIfAny(pushBytes: ByteArray) {
        formulaPhaseActive = true
        formulaPhaseFailCount = 0
        _pushState.update {
            it.copy(
                statusText = "开始同步公式图",
                isTransferring = true,
                isFinished = false,
                isSuccess = false,
                errorMessage = null
            )
        }
        formulaPushJob = viewModelScope.launch {
            val plans = withContext(Dispatchers.Default) {
                parseFormulaPlans(pushBytes)
            }
            if (plans.isEmpty()) {
                formulaPhaseActive = false
                pendingFormulaPlans = emptyList()
                _pushState.update {
                    it.copy(
                        progress = 1.0,
                        statusText = "传输完成",
                        isTransferring = false,
                        isFinished = true,
                        isSuccess = true,
                        errorMessage = null
                    )
                }
                _screen.value = AppScreen.Result
                return@launch
            }
            _pushState.update {
                it.copy(statusText = "开始同步公式图 0/${plans.size}")
            }
            val renderer = formulaRenderer
            if (renderer == null) {
                formulaPhaseActive = false
                pendingFormulaPlans = emptyList()
                _pushState.update {
                    it.copy(
                        statusText = "公式图渲染未初始化，跳过公式同步",
                        isTransferring = false,
                        isFinished = true,
                        isSuccess = true,
                        errorMessage = null
                    )
                }
                _screen.value = AppScreen.Result
                return@launch
            }
            try {
                Log.e("SnapNotesViewModel", "ALERT formula phase: ${plans.size} plans, renderer=${renderer != null}")
                for ((i, plan) in plans.withIndex()) {
                    if (!isActive) break
                    _pushState.update {
                        it.copy(statusText = "渲染公式图 ${i + 1}/${plans.size} · ${plan.subject}#${plan.id}")
                    }
                    val latexList = plan.formulas.map { raw -> RawToLatexConverter.convert(raw) }
                    Log.e("SnapNotesViewModel", "ALERT rendering formula ${i + 1}/${plans.size}: ${plan.subject}#${plan.id}")
                    val png = renderer.render(latexList)
                    if (png == null) {
                        formulaPhaseFailCount++
                        Log.e("SnapNotesViewModel", "formula render skip: ${plan.subject}#${plan.id}")
                        continue
                    }
                    Log.e("SnapNotesViewModel", "ALERT formula rendered ${i + 1}/${plans.size}: ${plan.subject}#${plan.id} ${png.width}x${png.height} ${png.bytes.size}B")
                    _pushState.update {
                        it.copy(statusText = "同步公式图 ${i + 1}/${plans.size} · ${plan.subject}#${plan.id}")
                    }
                    val activePusher = pusher
                    if (activePusher == null) {
                        formulaPhaseFailCount++
                        Log.e("SnapNotesViewModel", "pusher null, skip formula: ${plan.subject}#${plan.id}")
                        continue
                    }
                    runCatching {
                        Log.e("SnapNotesViewModel", "ALERT calling pushFormula: ${plan.subject}#${plan.id}")
                        activePusher.pushFormula(plan.subject, plan.id, png.bytes, png.width, png.height)
                        Log.e("SnapNotesViewModel", "ALERT pushFormula ok: ${plan.subject}#${plan.id}")
                    }.onFailure { e ->
                        formulaPhaseFailCount++
                        Log.e("SnapNotesViewModel", "formula push fail: ${plan.subject}#${plan.id}", e)
                    }
                }
            } finally {
                formulaPhaseActive = false
            }
            val failCount = formulaPhaseFailCount
            pendingFormulaPlans = emptyList()
            _pushState.update {
                it.copy(
                    progress = 1.0,
                    statusText = if (failCount == 0) {
                        "知识点与公式图已同步"
                    } else {
                        "知识点已同步，$failCount 个公式图未同步"
                    },
                    isTransferring = false,
                    isFinished = true,
                    isSuccess = true,
                    errorMessage = null,
                    preview = "共同步 ${plans.size - failCount}/${plans.size} 个公式图"
                )
            }
            _screen.value = AppScreen.Result
        }
    }

    /** 解析 JSON，提取每条含非空 formulas 的条目公式计划；id 缺省/非 number 用数组下标 j+1（与手环 mergeParsedInto 一致）。 */
    private fun parseFormulaPlans(bytes: ByteArray): List<FormulaPlan> {
        if (bytes.isEmpty()) return emptyList()
        return runCatching {
            val root = JSONTokener(bytes.toString(Charsets.UTF_8)).nextValue() as? JSONObject ?: return emptyList()
            val keys = root.keys()
            val plans = mutableListOf<FormulaPlan>()
            while (keys.hasNext()) {
                val subject = keys.next()
                val list = root.optJSONArray(subject) ?: continue
                for (j in 0 until list.length()) {
                    val item = list.optJSONObject(j) ?: continue
                    val formulas = item.optJSONArray("formulas") ?: continue
                    if (formulas.length() == 0) continue
                    val rawId = item.opt("id")
                    val id = if (rawId is Number) rawId.toInt() else (j + 1)
                    val formulaList = (0 until formulas.length()).map { formulas.optString(it) }.filter { it.isNotBlank() }
                    if (formulaList.isEmpty()) continue
                    plans.add(FormulaPlan(subject, id, formulaList))
                }
            }
            plans
        }.getOrElse { e ->
            Log.e("SnapNotesViewModel", "parse formula plans fail", e)
            emptyList()
        }
    }

    private suspend fun doPush(uri: Uri, folderId: String? = null) {
        val fileInfo = withContext(Dispatchers.IO) { getFileInfo(uri) }
        _selectedFile.value = fileInfo
        pendingPushUri = uri

        _pushState.value = PushState(
            fileName = fileInfo.fileName,
            fileSize = fileInfo.fileSize,
            statusText = "读取文件中",
            isTransferring = true
        )
        _screen.value = AppScreen.Progress

        try {
            val bytes = withContext(Dispatchers.IO) { readAndValidateJson(uri) }
            _pushState.update { it.copy(fileSize = bytes.size.toLong(), statusText = "连接手环中") }

            // 暂存本轮字节/文件名，推送成功后据此落缓存并记历史。
            pendingPushBytes = bytes
            pendingPushFileName = fileInfo.fileName

            ensureConnected()
            _pushState.update { it.copy(statusText = "准备发送") }

            val activePusher = pusher ?: throw IllegalStateException("通信层未初始化")
            activePusher.pushFile(bytes, fileInfo.fileName, folderId)
        } catch (e: Exception) {
            val message = e.message ?: "未知错误"
            Log.e("SnapNotesViewModel", "push fail", e)
            _pushState.update {
                it.copy(
                    statusText = message,
                    isTransferring = false,
                    isFinished = true,
                    isSuccess = false,
                    errorMessage = message
                )
            }
            _screen.value = AppScreen.Result
        }
    }

    private suspend fun ensureConnected() {
        val conn = connection ?: throw IllegalStateException("通信层未初始化")

        // 已建立连接：补一次握手即可，跳过 connect/auth/openApp 链路。
        if (_connectionState.value.isConnected) {
            runCatching { conn.init() }
            return
        }

        // 未连接：发起一次同步等待的重连。
        autoRetryJob?.cancel()
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        // 用一次性连接尝试并等待其完成。
        val connJob = viewModelScope.launch {
            try {
                withTimeout(10_000L.milliseconds) {
                    conn.destroy().await()
                    val deviceName = conn.connect().await()
                    val unsupportedDevices = listOf("小米手环8")
                    if (unsupportedDevices.any { it == deviceName }) {
                        _connectionState.update {
                            it.copy(statusText = "不受支持", descriptionText = "$deviceName 暂不支持", isConnected = false, deviceName = deviceName)
                        }
                        _connectionErrorState.value = ConnectionErrorState(
                            deviceName = deviceName, isUnsupportedDevice = true,
                            message = "$deviceName 暂不支持闪念小抄同步"
                        )
                        ready.completeExceptionally(IllegalStateException("$deviceName 暂不支持"))
                        return@withTimeout
                    }
                    _connectionState.update {
                        it.copy(statusText = "授权中", descriptionText = "$deviceName 正在授权", isConnected = false, deviceName = deviceName)
                    }
                    conn.auth().await()
                    if (!conn.getAppState().await()) {
                        _connectionState.update {
                            it.copy(statusText = "未安装", descriptionText = "请先在手环上安装闪念小抄快应用", isConnected = false, deviceName = deviceName)
                        }
                        _connectionErrorState.value = ConnectionErrorState(
                            deviceName = deviceName, message = "请先在手环上安装闪念小抄快应用"
                        )
                        ready.completeExceptionally(IllegalStateException("手环未安装闪念小抄"))
                        return@withTimeout
                    }
                    _connectionState.update {
                        it.copy(statusText = "拉起手环端", descriptionText = "$deviceName 正在打开导入页", isConnected = false, deviceName = deviceName)
                    }
                    conn.openApp().await()
                    conn.registerListener().await()
                    conn.init()
                    _connectionState.update {
                        it.copy(statusText = "连接成功", descriptionText = "$deviceName 已连接", isConnected = true, deviceName = deviceName)
                    }
                    ready.complete(Unit)
                }
            } catch (e: Exception) {
                markConnectionFailed(e.message ?: "连接失败")
                ready.completeExceptionally(e)
            }
        }

        try {
            ready.await()
        } catch (e: Exception) {
            connJob.cancel()
            throw e
        }
    }

    private fun markConnectionFailed(message: String) {
        _connectionState.update {
            it.copy(
                statusText = "连接失败",
                descriptionText = message,
                isConnected = false,
                deviceName = null
            )
        }
        _connectionErrorState.value = ConnectionErrorState(message = message)
        // 连不上时待命无意义：停前台服务避免空耗 + 无效通知（传输中除外）。
        if (!_pushState.value.isTransferring) {
            ForegroundTransferService.stopService(getApplication())
        }
    }

    private fun getFileInfo(uri: Uri): SelectedFileState {
        // file:// Uri（内置文件管理器选中的本地 File、编辑器导出的缓存文件等）
        // 经 ContentResolver.query 通常拿不到 OpenableColumns.SIZE，直接走 File API 最稳。
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = uri.path?.let { File(it) }
            val name = file?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "knowledge.json"
            val size = file?.let { if (it.exists() && it.isFile) it.length() else -1L } ?: -1L
            return SelectedFileState(uri, name, size)
        }

        val resolver = getApplication<Application>().contentResolver
        var fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "knowledge.json"
        var fileSize = -1L
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: fileName
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) fileSize = cursor.getLong(sizeIndex)
            }
        } finally {
            cursor?.close()
        }
        // 部分 content provider 不报 SIZE：回退用流长度兜底，避免显示"未知大小"。
        if (fileSize < 0) {
            runCatching {
                resolver.openInputStream(uri)?.use { it.available().toLong().let { sz -> fileSize = sz } }
            }
        }
        return SelectedFileState(uri, fileName, fileSize)
    }

    private fun readAndValidateJson(uri: Uri): ByteArray {
        // file:// Uri 直接走 File 读取，避免依赖 ContentResolver 对 file scheme 的支持差异
        // （内置文件管理器选中的本地文件多为 /storage/emulated/0/... 的 file Uri）。
        val bytes = if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: throw IllegalArgumentException("无法解析文件路径")
            val file = File(path)
            if (!file.exists()) throw IllegalArgumentException("文件不存在")
            if (file.length() > MAX_IMPORT_FILE_BYTES) {
                throw IllegalArgumentException("文件超过 50MB 上限，无法导入")
            }
            file.readBytes()
        } else {
            val resolver = getApplication<Application>().contentResolver
            // provider 若报 SIZE 先比大小，超限直接拒绝，避免超大文件整读进内存。
            var size = -1L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
                }
            }
            if (size > MAX_IMPORT_FILE_BYTES) {
                throw IllegalArgumentException("文件超过 50MB 上限，无法导入")
            }
            resolver.openInputStream(uri)?.use { it.readBounded(MAX_IMPORT_FILE_BYTES) }
                ?: throw IllegalArgumentException("无法读取文件")
        }
        if (bytes.isEmpty()) throw IllegalArgumentException("文件为空")

        val text = bytes.toString(Charsets.UTF_8)
        val root = JSONTokener(text).nextValue()
        if (root !is JSONObject) {
            throw IllegalArgumentException("知识点文件顶层必须是 JSON 对象")
        }
        if (root.length() == 0) {
            throw IllegalArgumentException("知识点文件为空对象")
        }
        return bytes
    }

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage = _snackbarMessage.asStateFlow()

    fun showSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    fun dismissSnackbar() {
        _snackbarMessage.value = null
    }

    /**
     * 从输入流读取，最多读 [maxBytes]；超限抛 [IllegalArgumentException]，
     * 防止 content provider 不报 SIZE 时把超大流整读进内存。
     */
    private fun InputStream.readBounded(maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IllegalArgumentException("文件超过 50MB 上限，无法导入")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /* ──────────── 文件夹创建（更多菜单入口） ──────────── */

    /** 应用专属知识库目录：文件夹创建的默认位置。 */
    private val snapnotesFolderDir by lazy {
        java.io.File(
            getApplication<Application>().getExternalFilesDir(null)
                ?: getApplication<Application>().filesDir,
            "SnapNotes"
        )
    }

    /**
     * 在应用专属知识库目录下创建子文件夹。
     * 已存在则提示；创建成功后通过 snackbar 反馈路径。
     */
    fun createFolder(folderName: String) {
        val name = folderName.trim()
        if (name.isBlank()) {
            _snackbarMessage.value = "文件夹名称不能为空"
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!snapnotesFolderDir.exists()) snapnotesFolderDir.mkdirs()
                    val folder = java.io.File(snapnotesFolderDir, name)
                    if (folder.exists()) {
                        "文件夹「$name」已存在"
                    } else {
                        folder.mkdirs()
                        "已创建文件夹：$name"
                    }
                }.getOrElse { e ->
                    "创建文件夹失败：${e.message ?: "未知错误"}"
                }
            }
            _snackbarMessage.value = result
        }
    }

    /* ──────────── 本地存储库管理方法 ──────────── */

    /** 本地存储根目录：app filesDir 下的 snapnotes_local。 */
    private val localRootDir: java.io.File by lazy {
        java.io.File(getApplication<Application>().filesDir, "snapnotes_local").apply { mkdirs() }
    }

    /** 刷新本地存储库当前目录内容。 */
    fun refreshLocalStorage() {
        val path = _localCurrentPath.value.ifBlank { localRootDir.absolutePath }
        val dir = java.io.File(path)
        if (!dir.exists() || !dir.isDirectory) {
            _localCurrentPath.value = localRootDir.absolutePath
            refreshLocalStorage()
            return
        }
        val folders = mutableListOf<com.whyy.snapnotes.ui.screens.LocalFolder>()
        val files = mutableListOf<com.whyy.snapnotes.ui.screens.LocalFile>()
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                folders.add(com.whyy.snapnotes.ui.screens.LocalFolder(f.name, f.absolutePath))
            } else if (f.isFile && (f.name.endsWith(".json") || f.name.endsWith(".JSON"))) {
                files.add(com.whyy.snapnotes.ui.screens.LocalFile(
                    name = f.name,
                    path = f.absolutePath,
                    size = f.length(),
                    lastModified = f.lastModified()
                ))
            }
        }
        folders.sortBy { it.name.lowercase() }
        files.sortBy { it.name.lowercase() }
        _localFolders.value = folders
        _localFiles.value = files
    }

    /** 进入指定本地文件夹（或回到根目录）。 */
    fun navigateLocalFolder(path: String) {
        val dir = java.io.File(path)
        if (dir.exists() && dir.isDirectory) {
            _localCurrentPath.value = dir.absolutePath
            refreshLocalStorage()
        }
    }

    /** 在当前本地目录下创建文件夹。 */
    fun createLocalFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _snackbarMessage.value = "文件夹名称不能为空"
            return
        }
        val parent = java.io.File(_localCurrentPath.value.ifBlank { localRootDir.absolutePath })
        val newDir = java.io.File(parent, trimmed)
        if (newDir.exists()) {
            _snackbarMessage.value = "文件夹已存在"
            return
        }
        if (newDir.mkdirs()) {
            _snackbarMessage.value = "已创建文件夹：$trimmed"
            refreshLocalStorage()
        } else {
            _snackbarMessage.value = "创建文件夹失败"
        }
    }

    /** 删除本地文件或文件夹。 */
    fun deleteLocalFile(file: java.io.File) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }
            _snackbarMessage.value = if (ok) "已删除" else "删除失败"
            refreshLocalStorage()
        }
    }

    /** 重命名本地文件或文件夹。 */
    fun renameLocalFile(file: java.io.File, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            _snackbarMessage.value = "名称不能为空"
            return
        }
        val target = java.io.File(file.parentFile, trimmed)
        if (target.exists()) {
            _snackbarMessage.value = "名称已存在"
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { file.renameTo(target) }
            _snackbarMessage.value = if (ok) "已重命名" else "重命名失败"
            refreshLocalStorage()
        }
    }

    /* ──────────── Amadeus 模型自动获取 ──────────── */

    /** 模型获取状态：null=未发起，空list=获取中，非空=可用模型列表。 */
    private val _amadeusModels = MutableStateFlow<List<String>?>(null)
    val amadeusModels = _amadeusModels.asStateFlow()

    private val _amadeusModelsLoading = MutableStateFlow(false)
    val amadeusModelsLoading = _amadeusModelsLoading.asStateFlow()

    /**
     * 通过当前配置的 Base URL + API Key 获取可用模型列表。
     * 调用 OpenAI 兼容的 GET /v1/models 接口。
     */
    fun fetchAvailableModels() {
        val cfg = _amadeus.value
        if (cfg.apiKey.isBlank()) {
            _snackbarMessage.value = "请先填写 API Key"
            return
        }
        val chat = amadeusChat ?: run {
            _snackbarMessage.value = "服务未就绪"
            return
        }
        _amadeusModelsLoading.value = true
        _amadeusModels.value = emptyList()
        viewModelScope.launch {
            val models = chat.fetchAvailableModels(cfg)
            _amadeusModels.value = models
            _amadeusModelsLoading.value = false
            if (models.isEmpty()) {
                _snackbarMessage.value = "未获取到可用模型，请检查配置"
            }
        }
    }

    fun clearAmadeusModels() {
        _amadeusModels.value = null
        _amadeusModelsLoading.value = false
    }

}

fun Long.toReadableBytes(): String {
    if (this < 0) return "未知大小"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = this.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "${this} ${units[unit]}" else "%.1f %s".format(value, units[unit])
}