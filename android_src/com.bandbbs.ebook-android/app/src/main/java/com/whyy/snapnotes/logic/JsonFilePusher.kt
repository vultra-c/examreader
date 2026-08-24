package com.whyy.snapnotes.logic

import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 手环存储空间查询结果（字节）。字段对齐参考项目 InterconnetFile.BandStorageInfoData。
 */
data class BandStorageInfoData(
    val product: String? = null,
    val totalStorage: Long = 0,
    val availableStorage: Long = 0,
    val reservedStorage: Long = 0,
    val usedStorage: Long = 0,
    val actualAvailable: Long = 0
) {
    /** 是否拿到了有效可用于展示的数据（总空间 > 0）。 */
    val hasValidData: Boolean get() = totalStorage > 0
}

/**
 * 闪念小抄单 JSON 文件分片推送层。
 *
 * 协议必须与手环端 `src/app.ux` 对齐：
 * - 手机→手环使用 `stat`: startTransfer / d / transferComplete / cancel
 * - 手环→手机使用 `type`: ready / next_chunk / transfer_finished / error
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class JsonFilePusher(private val conn: InterHandshake) {

    companion object {
        private const val TAG = "JsonFilePusher"
        const val CHUNK_SIZE = 10 * 1024
        private const val FIRST_PACKET_TIMEOUT_MS = 15_000L
        private const val PER_CHUNK_TIMEOUT_MS = 10_000L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * 共享 BLE 串行下发锁。
     *
     * 推书 / storage 查询 / Amadeus chat 回包共用同一条 BLE 通道，必须串行下发，
     * 否则手环端会把并发包吞掉。`AmadeusChat` 通过 [sendMutex] 复用此锁。
     */
    val sendMutex = Mutex()
    private var storageDeferred: CompletableDeferred<BandStorageInfoData>? = null

    @Volatile
    var busy: Boolean = false
        private set

    var onProgress: ((progress: Double, preview: String, status: String) -> Unit)? = null
    var onSuccess: ((message: String) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null
    /** 手环存储空间查询回包回调（非阻塞通知；getStorageInfo 另有 await 路径）。 */
    var onStorageInfo: ((BandStorageInfoData) -> Unit)? = null

    private var readyDeferred: CompletableDeferred<ReadyAck> = CompletableDeferred()
    private var nextChunkDeferred: CompletableDeferred<Unit> = CompletableDeferred()
    private var finishedDeferred: CompletableDeferred<Unit> = CompletableDeferred()

    private var chunks: List<String> = emptyList()
    private var currentChunkIndex = 0
    private var currentFileName = "knowledge.json"

    init {
        conn.addOnDisconnectedListener {
            if (busy) {
                failTransfer("连接断开")
            }
        }
        conn.addListener("file") { payload ->
            try {
                val header = json.decodeFromString<FileMessagesFromDevice.Header>(payload)
                // 存储空间查询回包走独立路径：不参与推送状态机，解析失败也不应破坏进行中的推送。
                if (header.type == "storage_info") {
                    handleStorageInfo(payload)
                    return@addListener
                }
                when (header.type) {
                    "ready" -> {
                        val ready = json.decodeFromString<FileMessagesFromDevice.Ready>(payload)
                        completeReady(ready.nextChunkIndex)
                    }
                    "next_chunk" -> completeNextChunk()
                    "transfer_finished" -> completeFinished()
                    "error" -> {
                        val error = json.decodeFromString<FileMessagesFromDevice.Error>(payload)
                        failTransfer(error.message.ifBlank { "手环返回未知错误" })
                    }
                    "success" -> {
                        // 手环端当前不会用 success 作为文件完成回执，但兼容保留。
                        val success = json.decodeFromString<FileMessagesFromDevice.Success>(payload)
                        Log.d(TAG, "success: ${success.message}")
                    }
                    else -> Log.d(TAG, "ignore file type=${header.type}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "parse file response fail", e)
                failTransfer("手环回执解析失败: ${e.message}")
            }
        }
    }

    /**
     * 查询手环存储空间。
     *
     * 协议：发 {tag:"file", stat:"get_storage_info"}，等回
     * {tag:"file", type:"storage_info", ...} 由 [handleStorageInfo] 解析后通过 [onStorageInfo] 回调上送。
     *
     * 与参考工程 `com.bandbbs.ebook-android/InterconnetFile.getStorageInfo` 完全对齐：
     * 本函数只负责把查询消息发出去（await 到「发送完成」即返回），**不 await 回包**；
     * 回包走异步 [onStorageInfo] 回调，回包何时到回调何时刷新，不会因为「发送后等回包」的超时而丢包。
     * 与推送共用 [sendMutex]（和参考一致），避免和推送在同一 BLE 通道上并发下发被吞。
     */
    suspend fun getStorageInfo(timeoutMs: Long = 8_000L): BandStorageInfoData = sendMutex.withLock {
        try {
            Log.d(TAG, "get_storage_info send...")
            withTimeout(timeoutMs) {
                conn.sendMessage(json.encodeToString(FileMessagesToSend.GetStorageInfo())).await()
            }
            Log.d(TAG, "get_storage_info sent ok; storage_info reply will arrive via onStorageInfo")
        } catch (e: Exception) {
            Log.e(TAG, "getStorageInfo fail: ${e.message}")
        }
        // 不 await 回包：返回当前缓存值（若有），回包异步刷新 StateFlow。
        // 这与参考工程行为一致——拿不到也只是 send 失败，回包迟到仍会经 onStorageInfo 上送。
        storageDeferred?.getCompleted() ?: BandStorageInfoData()
    }

    private fun handleStorageInfo(payload: String) {
        try {
            val info = json.decodeFromString<FileMessagesFromDevice.StorageInfo>(payload)
            val data = BandStorageInfoData(
                product = info.product,
                totalStorage = info.totalStorage,
                availableStorage = info.availableStorage,
                reservedStorage = info.reservedStorage,
                usedStorage = info.usedStorage,
                actualAvailable = info.actualAvailable
            )
            Log.d(TAG, "storage_info recv: total=${data.totalStorage} avail=${data.availableStorage} used=${data.usedStorage} product=${data.product}")
            onStorageInfo?.invoke(data)
            // 缓存最新值，供 getStorageInfo() 同步返回当前值（不靠 await 回包，避免超时丢包竞态）。
            storageDeferred = CompletableDeferred<BandStorageInfoData>().apply { complete(data) }
        } catch (e: Exception) {
            Log.e(TAG, "parse storage_info fail", e)
        }
    }

    suspend fun pushFile(jsonBytes: ByteArray, fileName: String, folderId: String? = null) = sendMutex.withLock {
        if (busy) {
            // 上一次推送还没结束（或卡在 await 永久挂起、busy 没复位）。
            // 静默 return 会让 UI 永远停在 0% 且无任何日志/错误，极难排查。
            // 这里改成抛错，让上层 doPush 的 catch 能感知并提示用户。
            throw IllegalStateException("上一次推送尚未结束，请稍候或重试")
        }
        resetDeferreds()
        busy = true
        currentFileName = fileName.ifBlank { "knowledge.json" }
        currentChunkIndex = 0

        try {
            val jsonText = jsonBytes.toString(Charsets.UTF_8)
            chunks = chunkUtf8Text(jsonText, CHUNK_SIZE)
            if (chunks.isEmpty()) throw IllegalArgumentException("JSON 文件为空")

            val totalChunks = chunks.size
            val totalBytes = jsonBytes.size.toLong()
            Log.e(TAG, "ALERT json transfer start: $currentFileName ${totalBytes}B $totalChunks chunks")
            onProgress?.invoke(0.0, currentFileName, "准备发送")

            val start = FileMessagesToSend.StartTransfer(
                filename = currentFileName,
                totalChunks = totalChunks,
                totalBytes = totalBytes,
                folderId = folderId
            )
            runTransfer(json.encodeToString(start), statusPrefix = "")
        } catch (e: TimeoutCancellationException) {
            failTransfer("传输超时，手环未响应")
        } catch (e: CancellationException) {
            failTransfer("传输已取消")
        } catch (e: Exception) {
            failTransfer("传输失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 推送一个知识点公式 PNG（startFormula 链路，与 JSON 推送共用同一状态机与 BLE 串行锁）。
     *
     * 协议与手环端 `src/app.ux` `_processFileMessage` 的 `startFormula` 分支对齐：
     * startFormula（含 subject/id/filename/w/h）→ ready → d 分片（base64，≤10KB）→
     * 每片 next_chunk → transferComplete → transfer_finished。
     * PNG 先整体 base64 再按 ≤10KB 切字符串（不要先切二进制再编码），与需求文档第 2.3 节一致。
     *
     * 文件名规则与内置一致：md5(subject#id) 前 12 位 + ".png"。
     */
    suspend fun pushFormula(
        subject: String,
        id: Int,
        pngBytes: ByteArray,
        w: Int,
        h: Int
    ) = sendMutex.withLock {
        if (busy) {
            throw IllegalStateException("上一次传输尚未结束，请稍候或重试")
        }
        resetDeferreds()
        busy = true
        currentFileName = formulaFileName(subject, id)
        currentChunkIndex = 0

        try {
            val base64Text = Base64.encodeToString(pngBytes, Base64.NO_WRAP)
            if (base64Text.isEmpty()) throw IllegalArgumentException("公式图片为空")
            chunks = chunkUtf8Text(base64Text, CHUNK_SIZE)
            if (chunks.isEmpty()) throw IllegalArgumentException("公式图片为空")

            val totalChunks = chunks.size
            val totalBytes = pngBytes.size.toLong()
            Log.e(TAG, "ALERT formula transfer start: $subject#$id -> $currentFileName ${totalBytes}B $totalChunks chunks w=${w}x${h}")
            onProgress?.invoke(0.0, currentFileName, "准备发送公式图")

            val start = FileMessagesToSend.StartFormula(
                subject = subject,
                id = id,
                filename = currentFileName,
                w = w,
                h = h,
                totalChunks = totalChunks,
                totalBytes = totalBytes
            )
            runTransfer(
                json.encodeToString(start),
                statusPrefix = "公式图 $subject#$id · "
            )
        } catch (e: TimeoutCancellationException) {
            failTransfer("传输超时，手环未响应")
        } catch (e: CancellationException) {
            failTransfer("传输已取消")
        } catch (e: Exception) {
            failTransfer("传输失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 通用分片传输主流程（startFormula / startTransfer 共用）：
     * 发首包 → 等 ready → 逐片发 d → 等 next_chunk → transferComplete → 等 transfer_finished。
     * 每一步 await 都带超时，保证 busy 必在有限时间内复位。
     */
    private suspend fun runTransfer(startMessage: String, statusPrefix: String = "") {
        // StartTransfer 下发加超时：InterHandshake.sendMessage 在握手 promise 卡死时
        // 会永不 complete，裸 await 会让 pushFile 永久持锁、busy 永不复位，UI 停 0% 无日志。
        Log.e(TAG, "runTransfer send start msg, waiting ready")
        sendWithTimeout(startMessage, FIRST_PACKET_TIMEOUT_MS)
        Log.e(TAG, "start message sent ok, waiting ready ...")

        val ready = withTimeout(FIRST_PACKET_TIMEOUT_MS) { readyDeferred.await() }
        Log.e(TAG, "ready recv nextChunkIndex=${ready.nextChunkIndex}, chunks=${chunks.size}")
        currentChunkIndex = ready.nextChunkIndex.coerceIn(0, chunks.size - 1)

        while (currentChunkIndex < chunks.size) {
            val index = currentChunkIndex
            Log.e(TAG, "sending chunk ${index + 1}/${chunks.size}")
            sendChunk(index, chunks.size, statusPrefix)
            if (index < chunks.size - 1) {
                withTimeout(PER_CHUNK_TIMEOUT_MS) { nextChunkDeferred.await() }
                nextChunkDeferred = CompletableDeferred()
                currentChunkIndex = index + 1
            } else {
                // 手环端即便最后一片也会先回 next_chunk；若回了就吃掉，未回也不阻塞。
                runCatching {
                    withTimeout(PER_CHUNK_TIMEOUT_MS) { nextChunkDeferred.await() }
                }
                currentChunkIndex = chunks.size
            }
        }

        onProgress?.invoke(0.99, currentFileName, "${statusPrefix}正在让手环写盘")
        Log.e(TAG, "sending transferComplete")
        sendWithTimeout(json.encodeToString(FileMessagesToSend.TransferComplete()))
        withTimeout(PER_CHUNK_TIMEOUT_MS) { finishedDeferred.await() }
        Log.e(TAG, "transfer_finished recv ok")

        onProgress?.invoke(1.0, currentFileName, "${statusPrefix}传输完成")
        onSuccess?.invoke("传输完成")
        resetState()
    }

    /**
     * 公式 PNG 文件名：md5(subject#id) 十六进制前 12 位 + ".png"。
     * 与手环内置 gen_formulas.js 命名算法一致；纯 ASCII 哈希命名避免中文/# 进 URI。
     */
    fun formulaFileName(subject: String, id: Int): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest("$subject#$id".toByteArray(Charsets.UTF_8))
        return bytes.take(6).joinToString("") { "%02x".format(it) } + ".png"
    }

    fun cancel() {
        conn.scope.launchCatching(TAG) {
            conn.sendMessage(json.encodeToString(FileMessagesToSend.Cancel())).await()
        }
        resetState()
        onSuccess?.invoke("用户取消")
    }

    /**
     * 强制复位推送通道：供 UI 在检测到「卡 0% 无响应」时复活通道用。
     * 正常路径每步 await 都已加超时，busy 必在有限时间内复位；此方法仅作兜底，
     * 当某次 pushFile 因非常规原因未走完 catch 时，让下一次推送至少能报错恢复而不是静默。
     */
    fun forceReset() {
        Log.w(TAG, "forceReset busy=$busy")
        resetState()
    }

    private suspend fun sendWithTimeout(message: String, timeoutMs: Long = PER_CHUNK_TIMEOUT_MS) {
        // 统一给 sendMessage 下发加超时。InterHandshake.sendMessage 在握手 promise 卡死时
        // 会永不 complete，裸 await 会让 pushFile 永久持锁、busy 永不复位。
        withTimeout(timeoutMs) { conn.sendMessage(message).await() }
    }

    private suspend fun sendChunk(index: Int, totalChunks: Int, statusPrefix: String = "") {
        val data = chunks[index]
        val message = FileMessagesToSend.DataChunk(
            chunkIndex = index,
            totalChunks = totalChunks,
            data = data
        )
        sendWithTimeout(json.encodeToString(message))
        val progress = (index + 1).toDouble() / totalChunks.toDouble()
        onProgress?.invoke(
            progress,
            currentFileName,
            "${statusPrefix}分片 ${index + 1}/$totalChunks"
        )
        Log.e(TAG, "chunk ${index + 1}/$totalChunks sent ${data.toByteArray(Charsets.UTF_8).size}B")
    }

    private fun completeReady(nextChunkIndex: Int) {
        if (!readyDeferred.isCompleted) {
            readyDeferred.complete(ReadyAck(nextChunkIndex))
        }
    }

    private fun completeNextChunk() {
        if (!nextChunkDeferred.isCompleted) {
            nextChunkDeferred.complete(Unit)
        }
    }

    private fun completeFinished() {
        if (!finishedDeferred.isCompleted) {
            finishedDeferred.complete(Unit)
        }
    }

    private fun failTransfer(message: String) {
        Log.e(TAG, message)
        readyDeferred.completeExceptionally(Exception(message))
        nextChunkDeferred.completeExceptionally(Exception(message))
        finishedDeferred.completeExceptionally(Exception(message))
        onError?.invoke(message)
        resetState()
    }

    private fun resetDeferreds() {
        readyDeferred = CompletableDeferred()
        nextChunkDeferred = CompletableDeferred()
        finishedDeferred = CompletableDeferred()
    }

    private fun resetState() {
        busy = false
        chunks = emptyList()
        currentChunkIndex = 0
        currentFileName = "knowledge.json"
        resetDeferreds()
    }

    /**
     * 按 UTF-8 字节上限切片，同时不切断 Unicode 字符。
     */
    private fun chunkUtf8Text(text: String, maxBytes: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        val builder = StringBuilder()
        var bytesInChunk = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val char = String(Character.toChars(codePoint))
            val charBytes = char.toByteArray(Charsets.UTF_8).size
            if (builder.isNotEmpty() && bytesInChunk + charBytes > maxBytes) {
                result.add(builder.toString())
                builder.clear()
                bytesInChunk = 0
            }
            builder.append(char)
            bytesInChunk += charBytes
            i += Character.charCount(codePoint)
        }
        if (builder.isNotEmpty()) result.add(builder.toString())
        return result
    }

    private data class ReadyAck(val nextChunkIndex: Int)

    @Serializable
    private sealed class FileMessagesFromDevice {
        @Serializable
        data class Header(val tag: String = "file", val type: String) : FileMessagesFromDevice()

        @Serializable
        data class Ready(
            val tag: String = "file",
            val type: String = "ready",
            val nextChunkIndex: Int = 0
        ) : FileMessagesFromDevice()

        @Serializable
        data class Error(
            val tag: String = "file",
            val type: String = "error",
            val message: String = ""
        ) : FileMessagesFromDevice()

        @Serializable
        data class Success(
            val tag: String = "file",
            val type: String = "success",
            val message: String = ""
        ) : FileMessagesFromDevice()

        @Serializable
        data class StorageInfo(
            val tag: String = "file",
            val type: String = "storage_info",
            val product: String? = null,
            val totalStorage: Long = 0,
            val availableStorage: Long = 0,
            val reservedStorage: Long = 0,
            val usedStorage: Long = 0,
            val actualAvailable: Long = 0
        ) : FileMessagesFromDevice()
    }

    @Serializable
    private sealed class FileMessagesToSend {
        @Serializable
        data class StartTransfer(
            val tag: String = "file",
            val stat: String = "startTransfer",
            val filename: String,
            val totalChunks: Int,
            val totalBytes: Long,
            val folderId: String? = null
        ) : FileMessagesToSend()

        @Serializable
        data class StartFormula(
            val tag: String = "file",
            val stat: String = "startFormula",
            val subject: String,
            val id: Int,
            val filename: String,
            val w: Int,
            val h: Int,
            val totalChunks: Int,
            val totalBytes: Long
        ) : FileMessagesToSend()

        @Serializable
        data class DataChunk(
            val tag: String = "file",
            val stat: String = "d",
            val chunkIndex: Int,
            val totalChunks: Int,
            val data: String
        ) : FileMessagesToSend()

        @Serializable
        data class TransferComplete(
            val tag: String = "file",
            val stat: String = "transferComplete"
        ) : FileMessagesToSend()

        @Serializable
        data class Cancel(
            val tag: String = "file",
            val stat: String = "cancel"
        ) : FileMessagesToSend()

        @Serializable
        data class GetStorageInfo(
            val tag: String = "file",
            val stat: String = "get_storage_info"
        ) : FileMessagesToSend()
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCatching(tag: String, block: suspend () -> Unit) {
    launch {
        try {
            block()
        } catch (e: Exception) {
            Log.e(tag, "background op fail", e)
        }
    }
}
