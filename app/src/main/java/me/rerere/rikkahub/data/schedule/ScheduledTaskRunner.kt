// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 主动任务执行器：定时 / 充电 / 低电量触发时，用指定助手在全新会话里发送提示词。

package me.rerere.rikkahub.data.schedule

import android.util.Log
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ScheduledTask
import me.rerere.rikkahub.data.model.ScheduledTaskTrigger
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import java.time.ZonedDateTime
import kotlin.uuid.Uuid

private const val TAG = "ScheduledTaskRunner"
private const val MAX_STALE_MS = 2 * 60 * 60 * 1000L

class ScheduledTaskRunner(
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val chatService: ChatService,
) {
    suspend fun runTask(task: ScheduledTask): Uuid? {
        val assistantId = runCatching { Uuid.parse(task.assistantId) }.getOrNull() ?: return null
        val conversation = Conversation(
            assistantId = assistantId,
            title = task.name.ifBlank { "主动任务" },
            messageNodes = emptyList(),
        )
        conversationRepository.insertConversation(conversation)
        chatService.sendMessage(
            conversationId = conversation.id,
            content = listOf(UIMessagePart.Text(task.prompt)),
            answer = true,
        )
        markRun(task.id, System.currentTimeMillis())
        Log.i(TAG, "ran task ${task.id} (${task.name}) in conversation ${conversation.id}")
        return conversation.id
    }

    suspend fun runDueTimeTasks(now: ZonedDateTime = ZonedDateTime.now()): Int {
        val tasks = settingsStore.settingsFlow.first().scheduledTasks
        var count = 0
        for (task in tasks) {
            if (!task.enabled || task.trigger != ScheduledTaskTrigger.TIME) continue
            if (now.dayOfWeek.value !in task.daysOfWeek) continue
            val scheduled = now.toLocalDate()
                .atTime(task.hour.coerceIn(0, 23), task.minute.coerceIn(0, 59))
                .atZone(now.zone)
                .toInstant()
                .toEpochMilli()
            val current = now.toInstant().toEpochMilli()
            if (current < scheduled) continue
            if (current - scheduled > MAX_STALE_MS) continue
            if (task.lastRunAt >= scheduled) continue
            if (runCatching { runTask(task) }.isSuccess) count++
        }
        return count
    }

    suspend fun runForTrigger(trigger: ScheduledTaskTrigger): Int {
        val tasks = settingsStore.settingsFlow.first().scheduledTasks
        var count = 0
        for (task in tasks) {
            if (!task.enabled || task.trigger != trigger) continue
            if (runCatching { runTask(task) }.isSuccess) count++
        }
        return count
    }

    private suspend fun markRun(taskId: String, at: Long) {
        settingsStore.update { settings ->
            settings.copy(
                scheduledTasks = settings.scheduledTasks.map {
                    if (it.id == taskId) it.copy(lastRunAt = at) else it
                }
            )
        }
    }
}
