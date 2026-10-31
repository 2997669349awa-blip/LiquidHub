// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 工作区后台任务前台服务：任务存在时保持进程存活，并驱动聚合通知。

package me.rerere.rikkahub.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.workspace.TaskNotificationHelper
import me.rerere.rikkahub.data.workspace.TaskOutputCache
import org.koin.android.ext.android.inject

private const val TAG = "WorkspaceTaskService"

/**
 * Foreground service keeping background shell tasks alive after the app is hidden.
 * Notification updates are driven by periodic polling of [BackgroundTaskManager].
 */
class WorkspaceTaskService : Service() {
    companion object {
        const val ACTION_SYNC = "me.rerere.rikkahub.action.WORKSPACE_TASK_SYNC"
        const val ACTION_STOP_TASK = "me.rerere.rikkahub.action.WORKSPACE_TASK_STOP"
        const val EXTRA_TASK_ID = "task_id"

        /** Starts (or pokes) the service so it observes the task list. */
        fun sync(context: Context) {
            val intent = Intent(context, WorkspaceTaskService::class.java).apply {
                action = ACTION_SYNC
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "Unable to start workspace task service", it) }
        }
    }

    private val taskManager: BackgroundTaskManager by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TASK -> {
                intent.getStringExtra(EXTRA_TASK_ID)?.let { taskId ->
                    taskManager.kill(taskId)
                    Log.d(TAG, "Stopped background task $taskId")
                }
                syncNotification()
            }

            else -> syncNotification()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    private fun syncNotification() {
        val tasks = taskManager.list()
        val running = tasks.filter { it.isRunning }
        if (running.isEmpty()) {
            stopService()
            return
        }
        running.forEach { task ->
            taskManager.readOutput(task.id, 30)?.let { TaskOutputCache.put(task.id, it) }
        }
        postNotification(taskManager.list())
        startLoop()
    }

    private fun postNotification(tasks: List<BackgroundShellTask>) {
        val notification = TaskNotificationHelper.build(this, tasks).build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    TaskNotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(TaskNotificationHelper.NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            stopService()
        }
    }

    /** Refresh notification every 2s to keep elapsed time and output current. */
    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                delay(2_000)
                val running = taskManager.list().filter { it.isRunning }
                if (running.isEmpty()) {
                    stopService()
                    break
                }
                running.forEach { task ->
                    taskManager.readOutput(task.id, 30)?.let { TaskOutputCache.put(task.id, it) }
                }
                val notification = TaskNotificationHelper.build(this@WorkspaceTaskService, taskManager.list()).build()
                if (isForeground) {
                    runCatching {
                        NotificationManagerCompat.from(this@WorkspaceTaskService)
                            .notify(TaskNotificationHelper.NOTIFICATION_ID, notification)
                    }
                } else {
                    postNotification(taskManager.list())
                }
            }
        }
    }

    private fun stopService() {
        loopJob?.cancel()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        TaskOutputCache.clear()
        stopSelf()
    }
}
