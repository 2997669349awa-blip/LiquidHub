package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.workspace.WorkspaceCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTaskManagerTest {

    private fun manager(scope: kotlinx.coroutines.CoroutineScope) = BackgroundTaskManager(scope = scope)

    @Test
    fun `ring buffer keeps only latest lines`() {
        val buffer = OutputRingBuffer(capacityLines = 3)
        buffer.append("1\n2\n")
        buffer.append("3\n4\n")
        assertEquals("2\n3\n4", buffer.snapshot())
        assertEquals("4", buffer.lastLine())
    }

    @Test
    fun `ring buffer exposes partial line`() {
        val buffer = OutputRingBuffer(capacityLines = 5)
        buffer.append("hello")
        buffer.append(" world")
        assertEquals("hello world", buffer.snapshot())
    }

    @Test
    fun `start completes with done and captures output`() = runBlocking {
        val manager = manager(this)
        val started = manager.start("ws", "root", "Work", "echo hi") { onOutput ->
            onOutput("hi\n")
            WorkspaceCommandResult(exitCode = 0, stdout = "hi", stderr = "")
        }

        val outcome = withTimeout(5_000) { started.result.await() }
        assertTrue(outcome is TaskOutcome.Completed)
        assertEquals(0, (outcome as TaskOutcome.Completed).result.exitCode)

        assertEquals(BackgroundTaskState.DONE, manager.find(started.task.id)?.state)
        assertEquals("hi", manager.readOutput(started.task.id)?.trim())
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `non zero exit code marks task failed`() = runBlocking {
        val manager = manager(this)
        val started = manager.start("ws", "root", "Work", "false") {
            WorkspaceCommandResult(exitCode = 2, stdout = "", stderr = "boom")
        }
        withTimeout(5_000) { started.result.await() }
        val task = manager.find(started.task.id)
        assertEquals(BackgroundTaskState.FAILED, task?.state)
        assertEquals(2, task?.exitCode)
    }

    @Test
    fun `timeout marks task failed`() = runBlocking {
        val manager = manager(this)
        val started = manager.start("ws", "root", "Work", "slow") {
            WorkspaceCommandResult(exitCode = -1, stdout = "", stderr = "", timedOut = true)
        }
        withTimeout(5_000) { started.result.await() }
        assertEquals(BackgroundTaskState.FAILED, manager.find(started.task.id)?.state)
    }

    @Test
    fun `detach returns immediately and task keeps running`() = runBlocking {
        val manager = manager(this)
        val gate = CompletableDeferred<Unit>()
        val started = manager.start("ws", "root", "Work", "long") { onOutput ->
            onOutput("working\n")
            gate.await()
            WorkspaceCommandResult(exitCode = 0, stdout = "done", stderr = "")
        }

        val id = started.task.id
        assertEquals(BackgroundTaskState.RUNNING, manager.find(id)?.state)
        assertTrue(manager.detach(id))

        val outcome = withTimeout(2_000) { started.result.await() }
        assertTrue(outcome is TaskOutcome.Detached)
        assertTrue(manager.isDetached(id))
        assertEquals(BackgroundTaskState.RUNNING, manager.find(id)?.state)

        gate.complete(Unit)
        withTimeout(5_000) {
            while (manager.find(id)?.state == BackgroundTaskState.RUNNING) delay(10)
        }
        assertEquals(BackgroundTaskState.DONE, manager.find(id)?.state)
        assertTrue(manager.isDetached(id))
    }

    @Test
    fun `kill stops running task and records history once`() = runBlocking {
        val manager = manager(this)
        val started = manager.start("ws", "root", "Work", "sleep") { onOutput ->
            onOutput("started\n")
            kotlinx.coroutines.awaitCancellation()
        }
        val id = started.task.id
        withTimeout(2_000) {
            while (manager.readOutput(id).isNullOrBlank()) delay(10)
        }

        assertTrue(manager.kill(id))
        withTimeout(2_000) {
            while (manager.find(id)?.state == BackgroundTaskState.RUNNING) delay(10)
        }
        assertEquals(BackgroundTaskState.KILLED, manager.find(id)?.state)
        assertEquals(1, manager.history().count { it.id == id })
        assertFalse(manager.kill(id))
    }

    @Test
    fun `history is capped at twenty entries`() = runBlocking {
        val manager = manager(this)
        repeat(25) { index ->
            val started = manager.start("ws", "root", "Work", "cmd$index") {
                WorkspaceCommandResult(exitCode = 0, stdout = "", stderr = "")
            }
            withTimeout(5_000) { started.result.await() }
        }
        assertEquals(20, manager.history().size)
        assertEquals("cmd24", manager.history().first().command)
    }

    @Test
    fun `markAllInterrupted flags running history entries`() = runBlocking {
        val manager = manager(this)
        val gate = CompletableDeferred<Unit>()
        val started = manager.start("ws", "root", "Work", "long") { gate.await(); WorkspaceCommandResult(0, "", "") }
        manager.detach(started.task.id)

        manager.markAllInterrupted()

        val task = manager.find(started.task.id)
        assertEquals(BackgroundTaskState.INTERRUPTED, task?.state)
        gate.complete(Unit)
        Unit
    }

    @Test
    fun `readOutput returns null for unknown task`() {
        val manager = BackgroundTaskManager()
        assertNull(manager.readOutput("missing"))
        assertNull(manager.find("missing"))
        assertFalse(manager.detach("missing"))
    }

    @Test
    fun `restoreHistory loads persisted tasks`() {
        val manager = BackgroundTaskManager()
        val task = BackgroundShellTask(
            id = "B001-1234",
            workspaceId = "ws",
            workspaceRoot = "root",
            workspaceName = "Work",
            command = "old",
            state = BackgroundTaskState.DONE,
            startedAtMs = 1L,
            endedAtMs = 2L,
            exitCode = 0,
        )
        manager.restoreHistory(listOf(task))
        assertEquals(listOf(task), manager.history())
        assertEquals(task, manager.find("B001-1234"))
    }
}
