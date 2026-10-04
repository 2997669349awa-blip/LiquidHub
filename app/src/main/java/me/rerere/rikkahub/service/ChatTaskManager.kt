// Modified by AI Hello World on 2026-10-04.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 生成任务注册表：每个生成任务分配唯一任务 ID，供输入框命令 //tasks / //kill-<id> / //recover-<id> 使用。

package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import kotlin.uuid.Uuid

object ChatTaskManager {
    data class Task(
        val id: String,
        val conversationId: Uuid,
        val startedAt: Long,
        val job: Job?,
    )

    private val active = LinkedHashMap<String, Task>()
    private val recent = ArrayDeque<Task>()
    private var counter = 0

    @Synchronized
    fun register(conversationId: Uuid, job: Job?): String {
        counter += 1
        val id = "T${counter.toString().padStart(3, '0')}-${(1000..9999).random()}"
        active[id] = Task(
            id = id,
            conversationId = conversationId,
            startedAt = System.currentTimeMillis(),
            job = job,
        )
        return id
    }

    @Synchronized
    fun complete(id: String) {
        val task = active.remove(id) ?: return
        recent.addFirst(task.copy(job = null))
        while (recent.size > 20) recent.removeLast()
    }

    @Synchronized
    fun activeTasks(): List<Task> = active.values.toList()

    @Synchronized
    fun find(id: String): Task? = active[id] ?: recent.firstOrNull { it.id == id }

    @Synchronized
    fun kill(id: String): Boolean {
        val task = active[id] ?: return false
        task.job?.cancel()
        return true
    }

    /** 按任务 ID 或会话 ID（前缀即可）停止任务。 */
    @Synchronized
    fun killByArg(arg: String): Boolean {
        val key = arg.trim()
        if (key.isEmpty()) return false
        active[key]?.let { it.job?.cancel(); return true }
        val match = active.values.firstOrNull {
            it.conversationId.toString().startsWith(key, ignoreCase = true)
        } ?: return false
        match.job?.cancel()
        return true
    }

    /** 按任务 ID 或会话 ID（前缀即可）查找任务（含最近完成的）。 */
    @Synchronized
    fun findByArg(arg: String): Task? {
        val key = arg.trim()
        if (key.isEmpty()) return null
        active[key]?.let { return it }
        recent.firstOrNull { it.id.equals(key, ignoreCase = true) }?.let { return it }
        return (active.values + recent).firstOrNull {
            it.conversationId.toString().startsWith(key, ignoreCase = true)
        }
    }

    /** 当前会话是否有进行中的任务。 */
    @Synchronized
    fun hasActive(conversationId: Uuid): Boolean =
        active.values.any { it.conversationId == conversationId }

    @Synchronized
    fun killAll(): Int {
        val count = active.size
        active.values.forEach { it.job?.cancel() }
        return count
    }
}
