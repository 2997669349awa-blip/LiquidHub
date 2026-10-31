// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 后台任务聚合通知：单条通知列出全部运行中/最近任务，可停止单个任务。

package me.rerere.rikkahub.data.workspace

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.service.BackgroundShellTask
import me.rerere.rikkahub.service.BackgroundTaskState
import me.rerere.rikkahub.service.WorkspaceTaskService

object TaskNotificationHelper {
    const val CHANNEL_ID = "workspace_tasks"
    const val NOTIFICATION_ID = 2003
    private const val MAX_STOP_ACTIONS = 3

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "工作区后台任务", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "%d:%02d:%02d".format(hours, minutes, seconds)
            else -> "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun stateLabel(task: BackgroundShellTask): String = when (task.state) {
        BackgroundTaskState.RUNNING -> "运行中"
        BackgroundTaskState.DONE -> "完成"
        BackgroundTaskState.FAILED -> "失败"
        BackgroundTaskState.KILLED -> "已停止"
        BackgroundTaskState.INTERRUPTED -> "已中断"
    }

    private fun stopAction(context: Context, task: BackgroundShellTask): NotificationCompat.Action {
        val intent = Intent(context, WorkspaceTaskService::class.java).apply {
            action = WorkspaceTaskService.ACTION_STOP_TASK
            putExtra(WorkspaceTaskService.EXTRA_TASK_ID, task.id)
        }
        val pending = PendingIntent.getService(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_rikkahub, "停止 ${task.id}", pending).build()
    }

    private fun contentIntent(context: Context, workspaceId: String?): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (workspaceId != null) putExtra("workspaceId", workspaceId)
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun build(context: Context, tasks: List<BackgroundShellTask>): NotificationCompat.Builder {
        ensureChannel(context)
        val running = tasks.filter { it.isRunning }
        val title = if (running.isEmpty()) {
            "工作区任务"
        } else {
            "工作区任务运行中 (${running.size})"
        }
        val inbox = NotificationCompat.InboxStyle()
        tasks.take(6).forEach { task ->
            val output = TaskOutputCache.peek(task.id)?.lineSequence()?.lastOrNull { it.isNotBlank() }
            val suffix = output?.take(48)?.let { " · $it" }.orEmpty()
            inbox.addLine(
                "[${task.id}] ${task.command.take(32)} · ${stateLabel(task)} · ${formatDuration(task.durationMs())}$suffix"
            )
        }
        if (tasks.size > 6) inbox.setSummaryText("等 ${tasks.size} 个任务")

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(title)
            .setContentText(tasks.firstOrNull()?.let { "${it.workspaceName} · ${it.command.take(60)}" } ?: "")
            .setStyle(inbox)
            .setContentIntent(contentIntent(context, running.firstOrNull()?.workspaceId ?: tasks.firstOrNull()?.workspaceId))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(running.isNotEmpty())
            .setOnlyAlertOnce(true)
            .setSilent(true)

        running.take(MAX_STOP_ACTIONS).forEach { task ->
            builder.addAction(stopAction(context, task))
        }
        return builder
    }
}

/** Latest output lines of a task, shared between the service and the notifier. */
object TaskOutputCache {
    private val outputs = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())

    fun put(taskId: String, output: String) {
        synchronized(outputs) {
            outputs[taskId] = output
            while (outputs.size > 20) {
                val oldest = outputs.keys.firstOrNull() ?: break
                outputs.remove(oldest)
            }
        }
    }

    fun peek(taskId: String): String? = outputs[taskId]

    fun clear() {
        outputs.clear()
    }
}
