// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 主动任务调度：每 15 分钟检查一次到点的定时任务。

package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.schedule.ScheduledTaskRunner
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ScheduledTaskWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    override suspend fun doWork(): Result {
        val runner: ScheduledTaskRunner by inject()
        return runCatching {
            runner.runDueTimeTasks()
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        const val UNIQUE_NAME = "scheduled-tasks-check"
        const val INTERVAL_MINUTES = 15L
    }
}
