package com.whyy.snapnotes.notifications

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * dataSync 前台服务：保活 + 系统级通知。两种态共用同一通知 id，互斥显示：
 * - 传输中：进度通知（标题随推送文件名、附进度条）。
 * - 待命（Amadeus 启用且已连手环、且不在传输）：常驻「Amadeus 待命 / 已连接 {device}」。
 *
 * 同一时刻通常只有一个态：传输开始会覆盖待命通知，传输结束若 Amadeus 仍启用则切回待命。
 */
class ForegroundTransferService : Service() {

    private var hasStartedForeground = false

    /** 进入 standby 模式时持锁：PARTIAL_WAKE_LOCK 压住 CPU 不进 deep idle，WifiLock 压住网络栈不睡。
     *  仅靠前台服务保进程不够 —— 息屏深度 Doze 会冻网络栈，OkHttp 新建连接直接 fail to connect；
     *  锁屏亮屏 Doze light 也会掐已建 socket（software caused connection abort）。持这两把锁是息屏
     *  后台可用的物理前提。代价是后台略耗电。 */
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        // Android 要求 onCreate 十秒内 startForeground，否则 ANR。先以中性标题占位，
        // 紧接着 onStartCommand(传输/待命) 会覆盖成真实标题。
        ensureForegroundStarted(MODE_TRANSFER, null, null, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getIntExtra(EXTRA_MODE, MODE_TRANSFER) ?: MODE_TRANSFER
        val title = intent?.getStringExtra(EXTRA_TITLE)
        val content = intent?.getStringExtra(EXTRA_CONTENT)
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, -1)
        ensureForegroundStarted(mode, title, content, progress?.takeIf { it >= 0 })
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseStandbyLocks()
        LiveNotificationManager.cancel()
        hasStartedForeground = false
    }

    private fun ensureForegroundStarted(
        mode: Int,
        title: String?,
        content: String?,
        progressPercent: Int?
    ) {
        // 待命态持锁压住网络栈不睡；传输态/其它态释放（传输时屏幕本就常亮，进程前台活跃，无须锁）。
        if (mode == MODE_STANDBY) acquireStandbyLocks() else releaseStandbyLocks()

        val notification: Notification = if (mode == MODE_STANDBY) {
            val safeTitle = title ?: "Amadeus 待命"
            if (hasStartedForeground) {
                LiveNotificationManager.showStandbyNotification(safeTitle, content)
                return
            }
            LiveNotificationManager.buildStandbyNotification(safeTitle, content)
        } else {
            val safeTitle = title ?: "传输中"
            if (hasStartedForeground) {
                LiveNotificationManager.showTransferNotification(safeTitle, content, progressPercent)
                return
            }
            LiveNotificationManager.buildTransferNotification(safeTitle, content, progressPercent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                LiveNotificationManager.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(LiveNotificationManager.NOTIFICATION_ID, notification)
        }
        hasStartedForeground = true
    }

    /** 息屏 Doze 下保 CPU 网络栈唤醒：acquire 两把锁（幂等，已持则跳过）。 */
    private fun acquireStandbyLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "snapnotes:amadeus_standby").apply {
                setReferenceCounted(false)
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL
            wifiLock = wm.createWifiLock(mode, "snapnotes:amadeus_standby").apply { setReferenceCounted(false) }
        }
        runCatching {
            if (wakeLock?.isHeld == false) wakeLock?.acquire(/* 不限时；release 由 standby 退出触发 */)
        }
        runCatching { if (wifiLock?.isHeld == false) wifiLock?.acquire() }
    }

    /** 释放待命锁（幂等）。切传输/关 Amadeus/服务销毁时调，防泄漏 + 省电。 */
    private fun releaseStandbyLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val MODE_TRANSFER = 0
        const val MODE_STANDBY = 1

        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_CONTENT = "extra_content"
        private const val EXTRA_PROGRESS = "extra_progress"
        private const val EXTRA_MODE = "extra_mode"

        /** 起传输态通知（带进度）。 */
        fun startService(context: Context, title: String, content: String?, progressPercent: Int?) {
            val intent = Intent(context, ForegroundTransferService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_TRANSFER)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CONTENT, content)
                putExtra(EXTRA_PROGRESS, progressPercent ?: -1)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 起待命态通知（无进度，常驻保活用）。 */
        fun startStandby(context: Context, title: String, content: String?) {
            val intent = Intent(context, ForegroundTransferService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_STANDBY)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CONTENT, content)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, ForegroundTransferService::class.java))
        }
    }
}
