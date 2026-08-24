package com.whyy.snapnotes

import android.app.Application
import android.app.NotificationManager
import com.whyy.snapnotes.data.initBuiltinData
import com.whyy.snapnotes.logic.InterHandshake
import com.whyy.snapnotes.notifications.LiveNotificationManager

class App : Application() {
    var conn: InterHandshake? = null

    override fun onCreate() {
        super.onCreate()
        initBuiltinData(this)
        val notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        LiveNotificationManager.initialize(this, notifManager)
    }
}
