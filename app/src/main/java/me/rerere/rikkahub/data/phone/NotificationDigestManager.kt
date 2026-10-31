// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 通知摘要缓存：仅保存在内存中，不落盘。

package me.rerere.rikkahub.data.phone

import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat

data class NotificationDigestEntry(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
)

object NotificationDigestManager {
    private const val MAX_ENTRIES = 50
    private val entries = ArrayDeque<NotificationDigestEntry>()

    fun isListenerEnabled(context: Context): Boolean =
        runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }.getOrDefault(false)

    @Synchronized
    fun record(sbn: StatusBarNotification, appLabel: String) {
        val notification = sbn.notification ?: return
        if (notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (
            extras.getCharSequence(NotificationCompat.EXTRA_TEXT)
                ?: extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)
            )?.toString().orEmpty().trim()
        if (title.isEmpty() && text.isEmpty()) return
        val entry = NotificationDigestEntry(
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title.take(200),
            text = text.take(500),
            postedAt = sbn.postTime,
        )
        // Replace an older entry from the same app with identical title to reduce noise.
        entries.removeAll { it.packageName == entry.packageName && it.title == entry.title }
        entries.addFirst(entry)
        while (entries.size > MAX_ENTRIES) entries.removeLast()
    }

    @Synchronized
    fun snapshot(limit: Int = 30): List<NotificationDigestEntry> = entries.take(limit)

    @Synchronized
    fun clear() = entries.clear()
}
