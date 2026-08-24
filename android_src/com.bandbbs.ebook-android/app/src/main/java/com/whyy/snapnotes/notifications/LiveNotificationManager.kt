package com.whyy.snapnotes.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.whyy.snapnotes.R
import com.whyy.snapnotes.ui.MainActivity

/**
 * 传输进度通知管理器。
 * 仅负责通知 channel / 构建 / 1s 节流；业务逻辑在 ViewModel / JsonFilePusher。
 */
object LiveNotificationManager {
    private lateinit var notificationManager: NotificationManager
    private lateinit var appContext: Context

    const val CHANNEL_ID = "transfer_channel_id"
    const val NOTIFICATION_ID = 2001

    private const val CHANNEL_NAME = "传输通知"
    private const val MIN_UPDATE_INTERVAL_MS = 1_000L
    private var lastUpdateTimeMs = 0L

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示闪念小抄同步进度"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun initialize(context: Context, notifManager: NotificationManager) {
        notificationManager = notifManager
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannel()
        }
    }

    fun showTransferNotification(
        title: String,
        contentText: String? = null,
        progressPercent: Int? = null
    ) {
        val nowMs = System.currentTimeMillis()
        if (lastUpdateTimeMs != 0L && nowMs - lastUpdateTimeMs < MIN_UPDATE_INTERVAL_MS) return
        lastUpdateTimeMs = nowMs

        notificationManager.notify(
            NOTIFICATION_ID,
            buildTransferNotification(title, contentText, progressPercent)
        )
    }

    fun buildTransferNotification(
        title: String,
        contentText: String? = null,
        progressPercent: Int? = null
    ): Notification {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(appContext, 0, launchIntent, flags)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setRequestPromotedOngoing(true)

        contentText?.let { builder.setContentText(it) }

        if (progressPercent == null || progressPercent <= 0) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, progressPercent.coerceIn(0, 100), false)
            if (contentText == null) builder.setContentText("$progressPercent%")
        }

        return builder.build()
    }

    /**
     * 构建常驻「Amadeus 待命」通知：无进度、低优先级 ongoing 点击回 App。
     * 复用同一 [NOTIFICATION_ID] 与传输通知互斥——同一时刻只显一种态（传输会覆盖待命）。
     */
    fun buildStandbyNotification(title: String, contentText: String?): Notification {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(appContext, 0, launchIntent, flags)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setRequestPromotedOngoing(true)
        contentText?.let { builder.setContentText(it) }
        return builder.build()
    }

    /** 把当前通知切为待命态（已起前台服务后调用，不走节流）。 */
    fun showStandbyNotification(title: String, contentText: String?) {
        notificationManager.notify(NOTIFICATION_ID, buildStandbyNotification(title, contentText))
    }

    fun cancel() {
        if (::notificationManager.isInitialized) {
            notificationManager.cancel(NOTIFICATION_ID)
        }
        lastUpdateTimeMs = 0L
    }
}
