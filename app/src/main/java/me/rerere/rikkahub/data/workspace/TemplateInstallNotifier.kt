// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 模板应用进度通知：在通知栏显示环境模板脚本的执行进度与结果。

package me.rerere.rikkahub.data.workspace

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.workspace.TemplateApplyProgress

object TemplateInstallNotifier {
    private const val CHANNEL_ID = "workspace_install"
    private const val NOTIFICATION_ID_BASE = 0x0D5D

    private fun notificationId(workspaceId: String): Int =
        NOTIFICATION_ID_BASE + (workspaceId.hashCode() and 0xFFFF)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "工作区模板安装", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun post(context: Context, workspaceId: String, builder: NotificationCompat.Builder) {
        ensureChannel(context)
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(workspaceId), builder.build())
        }
    }

    private fun base(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(me.rerere.rikkahub.R.drawable.ic_launcher_liquidhub_foreground)
            .setOnlyAlertOnce(true)

    fun start(context: Context, workspaceId: String, workspaceName: String, templateName: String) {
        post(
            context,
            workspaceId,
            base(context)
                .setContentTitle("正在配置环境模板")
                .setContentText("$workspaceName · $templateName · 准备环境…")
                .setProgress(0, 0, true)
                .setOngoing(true),
        )
    }

    fun progress(context: Context, workspaceId: String, workspaceName: String, progress: TemplateApplyProgress) {
        val title = "正在配置环境模板 (${progress.scriptIndex + 1}/${progress.totalScripts})"
        post(
            context,
            workspaceId,
            base(context)
                .setContentTitle(title)
                .setContentText("$workspaceName · ${progress.templateName} · ${progress.currentScriptName}")
                .setProgress(progress.totalScripts, progress.scriptIndex, false)
                .setOngoing(true),
        )
    }

    fun success(context: Context, workspaceId: String, templateName: String) {
        post(
            context,
            workspaceId,
            base(context)
                .setContentTitle("环境模板配置完成")
                .setContentText("$templateName 已就绪，可以让 AI 开始工作了")
                .setProgress(0, 0, false)
                .setAutoCancel(true),
        )
    }

    fun fail(
        context: Context,
        workspaceId: String,
        templateName: String,
        scriptName: String,
        logTail: String,
    ) {
        val summary = "步骤「$scriptName」失败 · $templateName"
        post(
            context,
            workspaceId,
            base(context)
                .setContentTitle("环境模板配置失败")
                .setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n\n$logTail"))
                .setProgress(0, 0, false)
                .setAutoCancel(true),
        )
    }
}
