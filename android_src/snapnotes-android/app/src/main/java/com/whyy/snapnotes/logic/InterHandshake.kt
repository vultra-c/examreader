package com.whyy.snapnotes.logic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * `__hs__` 握手/心跳层。
 *
 * 手机端主动发 count=0；手环端回 count=1 + BAND_VERSION_CODE；手机端回 count=2 后认为握手完成。
 * 握手完成后每 5s 发送 count=0 保活；10s 无消息则视为断开。
 */
class InterHandshake(context: Context, val scope: CoroutineScope) : Interconn(context) {

    companion object {
        private const val TYPE = "__hs__"
        private const val TIMEOUT = 10_000L
        private const val PING_INTERVAL = 5_000L
        /**
         * ensureHandshake 内部 await 手环回包的单次上限。比 [TIMEOUT] 短，且显著小于
         * JsonFilePusher.getStorageInfo 的 8s 上限：首次冷拉起拿不到手环应答时，尽快让
         * handshakePromise completeExceptionally 解锁，使上层补发的下一次 sendMessage 不再
         * 卡在已 pending 的旧 promise 上（否则 8s 之内永远补不出去）。
         */
        private const val HS_AWAIT_TIMEOUT = 6_000L

        const val PHONE_VERSION_CODE = 2
        const val MIN_BAND_VERSION_CODE = 2
        const val MIN_BAND_VERSION = "V1.0.1"
    }

    private var promise: CompletableDeferred<Unit>? = null
    private var connected = false
    private var isHandshaking = false

    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var pingRunnable: Runnable? = null

    var connectedBandVersion: Int? = null
        private set

    private var hasDispatchedBandVersion = false
    private val onDisconnectedListeners = mutableListOf<() -> Unit>()

    override fun onRawMessageReceived() {
        resetTimeout()
    }

    init {
        addListener(TYPE) { payload ->
            try {
                val data = json.decodeFromString<HandshakePayload>(payload)
                val currentCount = data.count
                val bandVersion = data.version

                connectedBandVersion = bandVersion
                if (bandVersion != null && !hasDispatchedBandVersion) {
                    hasDispatchedBandVersion = true
                    onBandVersionReceived.invoke(bandVersion)
                    if (bandVersion < MIN_BAND_VERSION_CODE) {
                        onVersionIncompatible.invoke(
                            bandVersion,
                            MIN_BAND_VERSION_CODE,
                            MIN_BAND_VERSION
                        )
                    }
                }

                if (promise != null && !connected) {
                    connected = true
                    isHandshaking = false
                    promise?.complete(Unit)
                    startPing()
                    onConnected.invoke()
                    Log.d("Handshake", "hs handshake done")
                }

                if (currentCount < 3) {
                    scope.launch {
                        try {
                            super.sendMessage(
                                "{\"tag\":\"$TYPE\",\"count\":${currentCount + 1},\"version\":$PHONE_VERSION_CODE}"
                            ).await()
                        } catch (e: Exception) {
                            Log.e("Handshake", "send hs reply fail", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Handshake", "handle hs payload fail", e)
            }
        }

        // 与参考工程对齐：构造时异步发握手首包。openApp 拉起手环快应用后，这条握手
        // 才会被手环端真正接收/回应；reconnect 无需再同步 await 握手完成（避免把
        // 「等快应用启动」塞进 reconnect 超时块导致首次必超时）。落地的握手完成由
        // 后续业务消息走 sendMessage→ensureHandshake 自行 await 兜底。
        scope.launch {
            try {
                super.sendMessage("{\"tag\":\"$TYPE\",\"count\":0,\"version\":$PHONE_VERSION_CODE}")
                    .await()
            } catch (e: Exception) {
                Log.e("Handshake", "initial handshake send fail", e)
            }
        }
    }

    fun addOnDisconnectedListener(callback: () -> Unit) {
        if (!onDisconnectedListeners.contains(callback)) {
            onDisconnectedListeners.add(callback)
        }
    }

    fun removeOnDisconnectedListener(callback: () -> Unit) {
        onDisconnectedListeners.remove(callback)
    }

    private fun resetTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = Runnable {
            Log.w("Handshake", "connection timeout")
            cleanup()
            onDisconnectedListeners.forEach { it.invoke() }
        }
        handler.postDelayed(timeoutRunnable!!, TIMEOUT)
    }

    private fun startPing() {
        pingRunnable?.let { handler.removeCallbacks(it) }
        pingRunnable = Runnable {
            if (connected) {
                scope.launch {
                    try {
                        super.sendMessage("{\"tag\":\"$TYPE\",\"count\":0,\"version\":$PHONE_VERSION_CODE}")
                            .await()
                    } catch (e: Exception) {
                        Log.e("Handshake", "ping fail", e)
                    }
                }
                pingRunnable?.let { handler.postDelayed(it, PING_INTERVAL) }
            }
        }
        handler.postDelayed(pingRunnable!!, PING_INTERVAL)
    }

    private fun cleanup() {
        promise?.cancel(CancellationException("connection cleanup"))
        promise = null
        connected = false
        isHandshaking = false
        hasDispatchedBandVersion = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        pingRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        pingRunnable = null
    }

    override fun sendMessage(message: String): CompletableDeferred<Unit> {
        val result = CompletableDeferred<Unit>()
        scope.launch {
            try {
                ensureHandshake()
                resetTimeout()
                super.sendMessage(message).await()
                result.complete(Unit)
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    private suspend fun ensureHandshake() {
        if (connected) return
        if (isHandshaking) {
            promise?.await()
            return
        }

        isHandshaking = true
        val handshakePromise = CompletableDeferred<Unit>()
        promise = handshakePromise

        val timeoutCb = Runnable {
            if (!connected) {
                isHandshaking = false
                promise = null
                handshakePromise.completeExceptionally(Exception("握手超时，请检查设备连接状态"))
            }
        }
        handler.postDelayed(timeoutCb, HS_AWAIT_TIMEOUT)

        try {
            super.sendMessage("{\"tag\":\"$TYPE\",\"count\":0,\"version\":$PHONE_VERSION_CODE}")
                .await()
            handshakePromise.await()
        } finally {
            handler.removeCallbacks(timeoutCb)
        }
    }

    /**
     * 强制复位握手中状态：清掉卡死 pending 的 handshakePromise 与 isHandshaking 标志，
     * 让下次 [sendMessage] 重新发一次 `__hs__ count=0`，而不是卡在旧 promise 上 await。
     *
     * 用途：上层在判定「本次握手迟迟不成（手环端 onmessage 还没挂好把首包丢了）」时主动复位，
     * 紧接着补发一次握手——手环 ready 后就能正常回 count=1 完成握手。不切断已建立的 connected
     * 状态（已连则不动）。
     */
    fun resetHandshake() {
        if (connected) return
        val p = promise
        promise = null
        isHandshaking = false
        if (p != null && !p.isCompleted) {
            p.completeExceptionally(CancellationException("handshake reset by caller"))
        }
    }

    override suspend fun init() {
        if (!connected && !isHandshaking) {
            try {
                ensureHandshake()
            } catch (e: Exception) {
                Log.e("Handshake", "manual init fail", e)
            }
        }
    }

    @Serializable
    private data class HandshakePayload(
        val count: Int,
        val tag: String,
        val version: Int? = null
    )

    private var onConnected: () -> Unit = {}
    fun setOnConnected(callback: () -> Unit) {
        onConnected = callback
    }

    private var onVersionIncompatible: (
        currentVersion: Int,
        requiredVersion: Int,
        requiredVersionName: String
    ) -> Unit = { _, _, _ -> }

    fun setOnVersionIncompatible(
        callback: (currentVersion: Int, requiredVersion: Int, requiredVersionName: String) -> Unit
    ) {
        onVersionIncompatible = callback
    }

    private var onBandVersionReceived: (version: Int) -> Unit = {}
    fun setOnBandVersionReceived(callback: (version: Int) -> Unit) {
        onBandVersionReceived = callback
    }
}
