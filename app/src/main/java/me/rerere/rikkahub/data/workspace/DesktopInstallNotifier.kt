// Modified by AI Hello World on 2026-10-04.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 桌面安装进度通知：在通知栏显示安装进度，完成后提示结果。

package me.rerere.rikkahub.data.workspace

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.R

object DesktopInstallNotifier {
    private const val CHANNEL_ID = "desktop_install"
    private const val NOTIFICATION_ID = 0x0D5C

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "桌面安装", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun post(context: Context, builder: NotificationCompat.Builder) {
        ensureChannel(context)
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun base(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_liquidhub_foreground)
            .setOnlyAlertOnce(true)

    fun start(context: Context, reinstall: Boolean) {
        post(
            context,
            base(context)
                .setContentTitle(if (reinstall) "正在重装桌面" else "正在安装桌面")
                .setContentText("正在下载并安装桌面组件…")
                .setProgress(0, 0, true)
                .setOngoing(true),
        )
    }

    fun progress(context: Context, line: String) {
        post(
            context,
            base(context)
                .setContentTitle("正在安装桌面")
                .setContentText(line.take(80))
                .setStyle(NotificationCompat.BigTextStyle().bigText(line))
                .setProgress(0, 0, true)
                .setOngoing(true),
        )
    }

    fun success(context: Context) {
        post(
            context,
            base(context)
                .setContentTitle("桌面安装完成")
                .setContentText("现在可以让 AI 启动远程桌面了")
                .setAutoCancel(true),
        )
    }

    fun fail(context: Context, reason: String) {
        post(
            context,
            base(context)
                .setContentTitle("桌面安装失败")
                .setContentText(reason.take(100))
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setAutoCancel(true),
        )
    }
}
