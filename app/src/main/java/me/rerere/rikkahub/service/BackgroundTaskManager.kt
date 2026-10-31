// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 后台 shell 任务注册表：长命令可脱离聊天协程在独立 scope 中继续执行，
// 通知栏与 AI 工具均通过这里查询任务状态与输出。

package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.workspace.WorkspaceCommandResult

@Serializable
enum class BackgroundTaskState {
    @SerialName("running")
    RUNNING,

    @SerialName("done")
    DONE,

    @SerialName("failed")
    FAILED,

    @SerialName("killed")
    KILLED,

    @SerialName("interrupted")
    INTERRUPTED,
}

@Serializable
data class BackgroundShellTask(
    val id: String,
    val workspaceId: String,
    val workspaceRoot: String,
    val workspaceName: String,
    val command: String,
    val state: BackgroundTaskState,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val exitCode: Int? = null,
    val detached: Boolean = false,
) {
    val isRunning: Boolean get() = state == BackgroundTaskState.RUNNING

    fun durationMs(now: Long = System.currentTimeMillis()): Long =
        (endedAtMs ?: now) - startedAtMs
}

/** Result handed back to the waiting tool call. */
sealed interface TaskOutcome {
    data class Completed(val result: WorkspaceCommandResult) : TaskOutcome

    /** Task was detached with `//bg`; the command keeps running in background. */
    data object Detached : TaskOutcome
}

/**
 * Fixed-size line buffer keeping the most recent output of a background task.
 * Partial (unterminated) lines are flushed when more output arrives or on [snapshot].
 */
class OutputRingBuffer(private val capacityLines: Int = 500) {
    private val lines = ArrayDeque<String>()
    private val pending = StringBuilder()

    @Synchronized
    fun append(chunk: String) {
        if (chunk.isEmpty()) return
        pending.append(chunk)
        while (true) {
            val newline = pending.indexOf("\n")
            if (newline < 0) break
            val line = pending.substring(0, newline).trimEnd('\r')
            pending.delete(0, newline + 1)
            push(line)
        }
        // Guard against a command producing one gigantic line without newlines.
        if (pending.length > MAX_PENDING_CHARS) {
            push(pending.toString())
            pending.clear()
        }
    }

    private fun push(line: String) {
        lines.addLast(line)
        while (lines.size > capacityLines) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(maxLines: Int = capacityLines): String {
        val all = ArrayList<String>(lines.size + 1)
        all.addAll(lines)
        if (pending.isNotEmpty()) all.add(pending.toString())
        val start = (all.size - maxLines).coerceAtLeast(0)
        return all.subList(start, all.size).joinToString("\n")
    }

    @Synchronized
    fun lastLine(): String? =
        when {
            pending.isNotEmpty() -> pending.toString()
            lines.isNotEmpty() -> lines.last()
            else -> null
        }

    @Synchronized
    fun clear() {
        lines.clear()
        pending.clear()
    }

    companion object {
        private const val MAX_PENDING_CHARS = 8 * 1024
    }
}

/**
 * Owns background shell tasks. Tasks run in [scope] (independent of any chat
 * generation coroutine); a caller that does not detach simply awaits [Started.result].
 */
class BackgroundTaskManager(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Started(
        val task: BackgroundShellTask,
        val result: CompletableDeferred<TaskOutcome>,
    )

    private class Record(
        var task: BackgroundShellTask,
        var job: Job? = null,
        var finished: Boolean = false,
        val result: CompletableDeferred<TaskOutcome> = CompletableDeferred(),
        val buffer: OutputRingBuffer = OutputRingBuffer(),
    )

    private val lock = Any()
    private val records = LinkedHashMap<String, Record>()
    private val history = ArrayDeque<BackgroundShellTask>()
    private var counter = 0

    private val _tasksFlow = MutableStateFlow<List<BackgroundShellTask>>(emptyList())
    val tasksFlow: StateFlow<List<BackgroundShellTask>> = _tasksFlow.asStateFlow()

    /** Persistence hook: invoked with the latest history after every change. */
    var historyListener: ((List<BackgroundShellTask>) -> Unit)? = null

    private fun notifyHistory() {
        val snapshot = history()
        historyListener?.invoke(snapshot)
    }

    private fun nextId(): String {
        counter += 1
        return "B${counter.toString().padStart(3, '0')}-${(1000..9999).random()}"
    }

    private fun publish() {
        _tasksFlow.value = synchronized(lock) { records.values.map { it.task } }
    }

    fun start(
        workspaceId: String,
        workspaceRoot: String,
        workspaceName: String,
        command: String,
        executor: suspend (onOutput: (String) -> Unit) -> WorkspaceCommandResult,
    ): Started {
        val record = synchronized(lock) {
            val id = nextId()
            val task = BackgroundShellTask(
                id = id,
                workspaceId = workspaceId,
                workspaceRoot = workspaceRoot,
                workspaceName = workspaceName,
                command = command,
                state = BackgroundTaskState.RUNNING,
                startedAtMs = clock(),
            )
            Record(task = task).also { records[id] = it }
        }
        publish()

        record.job = scope.launch {
            val result = try {
                executor { chunk -> record.buffer.append(chunk) }
            } catch (e: CancellationException) {
                finish(record, BackgroundTaskState.KILLED, exitCode = null)
                throw e
            } catch (e: Throwable) {
                record.buffer.append("\n[error] ${e.message ?: e::class.simpleName}")
                finish(record, BackgroundTaskState.FAILED, exitCode = -1)
                return@launch
            }
            val state = when {
                result.timedOut -> BackgroundTaskState.FAILED
                result.exitCode == 0 -> BackgroundTaskState.DONE
                else -> BackgroundTaskState.FAILED
            }
            finish(record, state, result.exitCode)
            record.result.complete(TaskOutcome.Completed(result))
        }

        return Started(record.task, record.result)
    }

    private fun finish(record: Record, state: BackgroundTaskState, exitCode: Int?) {
        synchronized(lock) {
            if (record.finished) return
            record.finished = true
            record.task = record.task.copy(
                state = state,
                endedAtMs = clock(),
                exitCode = exitCode,
            )
            records.remove(record.task.id)
            history.addFirst(record.task)
            while (history.size > MAX_HISTORY) history.removeLast()
            persistedOutput[record.task.id] = record.buffer.snapshot(PERSISTED_OUTPUT_LINES)
            keepPersistedOutputBounded()
        }
        publish()
        notifyHistory()
        if (!record.result.isCompleted) {
            record.result.complete(
                TaskOutcome.Completed(
                    WorkspaceCommandResult(
                        exitCode = exitCode ?: -1,
                        stdout = record.buffer.snapshot(),
                        stderr = "",
                        truncated = false,
                    ),
                ),
            )
        }
    }

    /** Detaches the waiting caller; the command keeps running. Returns false if unknown/not running. */
    fun detach(taskId: String): Boolean = synchronized(lock) {
        val record = records[taskId] ?: return false
        record.task = record.task.copy(detached = true)
        record.result.complete(TaskOutcome.Detached)
        publish()
        true
    }

    fun isDetached(taskId: String): Boolean = synchronized(lock) {
        records[taskId]?.task?.detached ?: history.firstOrNull { it.id == taskId }?.detached ?: false
    }

    fun list(): List<BackgroundShellTask> = synchronized(lock) { records.values.map { it.task } }

    fun history(): List<BackgroundShellTask> = synchronized(lock) { history.toList() }

    fun find(taskId: String): BackgroundShellTask? = synchronized(lock) {
        records[taskId]?.task ?: history.firstOrNull { it.id == taskId }
    }

    fun readOutput(taskId: String, maxLines: Int = 200): String? = synchronized(lock) {
        records[taskId]?.buffer?.snapshot(maxLines) ?: persistedOutput[taskId]
    }

    /** Output snapshots of finished tasks (bounded to history size). */
    private val persistedOutput = LinkedHashMap<String, String>()

    private fun keepPersistedOutputBounded() {
        while (persistedOutput.size > MAX_HISTORY) {
            val oldest = persistedOutput.keys.first()
            persistedOutput.remove(oldest)
        }
    }

    fun kill(taskId: String): Boolean = synchronized(lock) {
        val record = records[taskId] ?: return false
        if (record.finished) return false
        record.finished = true
        record.job?.cancel()
        record.result.complete(TaskOutcome.Completed(WorkspaceCommandResult(-1, record.buffer.snapshot(), "", false, false)))
        record.task = record.task.copy(state = BackgroundTaskState.KILLED, endedAtMs = clock())
        records.remove(taskId)
        history.addFirst(record.task)
        while (history.size > MAX_HISTORY) history.removeLast()
        persistedOutput[taskId] = record.buffer.snapshot(PERSISTED_OUTPUT_LINES)
        keepPersistedOutputBounded()
        publish()
        notifyHistory()
        true
    }

    fun killAll(): Int = synchronized(lock) { records.keys.toList() }.count { kill(it) }

    /** Called on app start: tasks left RUNNING from a previous process are dead. */
    fun markAllInterrupted() {
        synchronized(lock) {
            val now = clock()
            records.values.forEach { record ->
                if (!record.finished) {
                    record.finished = true
                    val interrupted = record.task.copy(state = BackgroundTaskState.INTERRUPTED, endedAtMs = now)
                    history.addFirst(interrupted)
                    persistedOutput[interrupted.id] = record.buffer.snapshot(PERSISTED_OUTPUT_LINES)
                }
            }
            records.clear()
            history.replaceAll { task ->
                if (task.isRunning) {
                    task.copy(state = BackgroundTaskState.INTERRUPTED, endedAtMs = now)
                } else {
                    task
                }
            }
            while (history.size > MAX_HISTORY) history.removeLast()
            keepPersistedOutputBounded()
        }
        publish()
        notifyHistory()
    }

    /** Restores task history loaded from persistence. */
    fun restoreHistory(tasks: List<BackgroundShellTask>) {
        synchronized(lock) {
            history.clear()
            tasks.take(MAX_HISTORY).forEach { history.addLast(it) }
        }
        publish()
    }

    companion object {
        private const val MAX_HISTORY = 20
        private const val PERSISTED_OUTPUT_LINES = 500
    }
}
