// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 通知监听服务：把最近通知收集到内存，供 AI 摘要与建议回复。

package me.rerere.rikkahub.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import me.rerere.rikkahub.data.phone.NotificationDigestManager

class AppNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName == packageName) return
        val label = runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(notification.packageName, 0)).toString()
        }.getOrDefault(notification.packageName)
        NotificationDigestManager.record(notification, label)
    }
}
