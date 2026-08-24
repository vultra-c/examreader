package com.whyy.snapnotes.ui.viewmodel

import android.net.Uri
import com.whyy.snapnotes.logic.BandStorageInfoData

data class ConnectionState(
    val statusText: String = "手环未连接",
    val descriptionText: String = "请选择 JSON 文件后开始连接",
    val isConnected: Boolean = false,
    val deviceName: String? = null
)

data class ConnectionErrorState(
    val deviceName: String? = null,
    val isUnsupportedDevice: Boolean = false,
    val message: String = "连接失败，请检查小米运动健康是否已连接手环。"
)

data class VersionIncompatibleState(
    val currentVersion: Int,
    val requiredVersion: Int,
    val requiredVersionName: String
)

/**
 * 单项排查结果。
 * - [Pass] 已解决（打勾）
 * - [Fail] 未解决（打差）
 * - [Checking] 检测中
 * - [NotApplicable] 当前条件无法检测（如未连上手环时无法查「是否已装闪念小抄」）—— UI 灰态问号
 */
enum class CheckResult { Pass, Fail, Checking, NotApplicable }

/**
 * 排查页状态。三项链路依赖逐项检查：蓝牙 → 小米运动健康连上设备 → 手环已装闪念小抄。
 * 后项依赖前项：前项未 [CheckResult.Pass] 时后项置 [CheckResult.NotApplicable]。
 * 三项全 [CheckResult.Pass] 时 [allVerifiablePassed] = true，触发自动重连。
 */
data class TroubleshootState(
    val bluetooth: CheckResult = CheckResult.Checking,
    val deviceConnected: CheckResult = CheckResult.Checking,
    val appInstalled: CheckResult = CheckResult.NotApplicable,
    val autoRetrying: Boolean = false,
    val bluetoothPermissionGranted: Boolean = true
) {
    val allVerifiablePassed: Boolean
        get() = bluetooth == CheckResult.Pass &&
                deviceConnected == CheckResult.Pass &&
                appInstalled == CheckResult.Pass
}

data class SelectedFileState(
    val uri: Uri,
    val fileName: String,
    val fileSize: Long
)

data class PushState(
    val fileName: String = "",
    val fileSize: Long = 0L,
    val progress: Double = 0.0,
    val preview: String = "",
    val statusText: String = "等待中",
    val isTransferring: Boolean = false,
    val isFinished: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null
)

/**
 * 一个知识点的公式图推送计划（从 JSON 的含 formulas 条目解析）。
 * [id] 与手环端 mergeParsedInto 规则一致：条目有 number 类型 id 用之，否则用数组下标 j+1。
 */
data class FormulaPlan(
    val subject: String,
    val id: Int,
    val formulas: List<String>
)

enum class AppScreen {
    Home,
    Settings,
    Progress,
    Result,
    Editor,
    History
}

/**
 * 一条「推送历史」记录。仅手机端记账，不反映手环实际内容。
 *
 * 注意：手环端把所有知识点合并成单一仓库、按「科目名 + id」增量 merge（同 id 不覆盖、
 * 不可单条删除）。本记录只是「这个文件曾经推送成功过」，删除本记录只删本地缓存与清单，
 * 不会删除手环上已导入的内容。重新推送等价于再次走 merge 链路，新增科目/不撞号条目会进，
 * 撞号条目仍被手环跳过。
 */
data class PushRecord(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val pushedAt: Long,           // epoch millis
    val cacheFileName: String,    // filesDir 下的缓存副本文件名
    val subjects: List<String>    // 该 JSON 顶层科目名，用于列表展示
)

data class EditorSubject(
    val name: String = "",
    val entries: List<EditorEntry> = emptyList()
)

data class EditorEntry(
    val id: String = "",
    val title: String = "",
    val desc: String = "",
    val raw: String = "",
    val points: List<String> = emptyList(),
    val formulas: List<String> = emptyList()
)


data class ExportResult(
    val success: Boolean,
    val message: String
)

/**
 * 手环端 Amadeus AI 聊天助手的手机端配置。全字段只在手机端存/用，手环不传不存不感知
 * （详见根目录「手机端AI聊天适配说明.md」第五节）。本结构只承载 UI 填写与持久化，
 * 真正接 LLM 网络调用是后续任务；现在只把这几项落进_prefs_。
 *
 * - [baseUrl] 空 = 走厂商默认；否则填厂商 API 根（如 https://api.deepseek.com）
 * - [apiKey] 明文存 prefs（与本工程其它 prefs 一致，不加密）
 * - [proxy] 空 = 不走代理；否则 "host:port"
 * - [timeoutSec] LLM 单次调用上限，文档第五节要求 ≤30s；下拉只给 15/30/60
 */
data class AmadeusConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val proxy: String = "",
    val timeoutSec: Int = 30
) {
    /** 是否已配齐「可发起调用」的最小集合：启用 + 有 key + 有 model。供入口卡片状态点用。 */
    val isReady: Boolean get() = enabled && apiKey.isNotBlank() && model.isNotBlank()
    /** 启用但缺 key 或 model——黄态提示用户配置不完整。 */
    val isHalfConfigured: Boolean get() = enabled && !isReady
}
