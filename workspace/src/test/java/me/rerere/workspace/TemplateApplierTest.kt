package me.rerere.workspace

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TemplateApplierTest {
    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(): File {
        val dir = File.createTempFile("template-applier-test", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        tempDirs += dir
        return dir
    }

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private class RecordingRunner(
        private val results: Map<String, WorkspaceCommandResult> = emptyMap(),
        private val default: WorkspaceCommandResult = WorkspaceCommandResult(0, "", ""),
    ) : WorkspaceShellRunner {
        val executed = mutableListOf<String>()

        override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
            executed += context.command
            return results[context.command] ?: default
        }
    }

    private fun buildManager(runner: WorkspaceShellRunner, base: File): WorkspaceManager =
        WorkspaceManager(baseDir = base, shellRunner = runner)

    @Test
    fun `apply runs scripts in order and returns success`() {
        val runner = RecordingRunner()
        val manager = buildManager(runner, newTempDir())
        manager.ensureWorkspace("ws")
        val applier = TemplateApplier(manager)

        val template = WorkspaceTemplate(
            id = "t1",
            name = "Test",
            description = "",
            scripts = listOf(
                TemplateScript(name = "one", command = "echo 1"),
                TemplateScript(name = "two", command = "echo 2"),
            ),
        )

        val result = applier.apply("ws", template)

        assertEquals(TemplateApplyResult.Success, result)
        assertEquals(listOf("echo 1", "echo 2"), runner.executed)
    }

    @Test
    fun `apply stops at first failing script`() {
        val runner = RecordingRunner(
            results = mapOf("fail" to WorkspaceCommandResult(exitCode = 2, stdout = "", stderr = "boom")),
        )
        val manager = buildManager(runner, newTempDir())
        manager.ensureWorkspace("ws")
        val applier = TemplateApplier(manager)

        val template = WorkspaceTemplate(
            id = "t2",
            name = "Test",
            description = "",
            scripts = listOf(
                TemplateScript(name = "ok", command = "ok"),
                TemplateScript(name = "bad", command = "fail"),
                TemplateScript(name = "never", command = "never"),
            ),
        )

        val result = applier.apply("ws", template)

        assertTrue(result is TemplateApplyResult.Failed)
        result as TemplateApplyResult.Failed
        assertEquals(1, result.scriptIndex)
        assertEquals("bad", result.script.name)
        assertEquals(2, result.exitCode)
        assertEquals(listOf("ok", "fail"), runner.executed)
    }

    @Test
    fun `apply reports failure on timeout`() {
        val runner = RecordingRunner(
            results = mapOf(
                "slow" to WorkspaceCommandResult(exitCode = -1, stdout = "", stderr = "", timedOut = true),
            ),
        )
        val manager = buildManager(runner, newTempDir())
        manager.ensureWorkspace("ws")
        val applier = TemplateApplier(manager)

        val template = WorkspaceTemplate(
            id = "t3",
            name = "Test",
            description = "",
            scripts = listOf(TemplateScript(name = "slow", command = "slow")),
        )

        val result = applier.apply("ws", template)
        assertTrue(result is TemplateApplyResult.Failed)
    }

    @Test
    fun `commandFor selects package manager specific command`() {
        val script = TemplateScript(
            name = "install",
            command = "generic",
            apkCommand = "apk install",
            aptCommand = "apt install",
            pacmanCommand = "pacman install",
        )

        assertEquals("apk install", script.commandFor(WorkspacePackageManager.APK))
        assertEquals("apt install", script.commandFor(WorkspacePackageManager.APT))
        assertEquals("pacman install", script.commandFor(WorkspacePackageManager.PACMAN))
        assertEquals("generic", script.commandFor(WorkspacePackageManager.UNKNOWN))
    }

    @Test
    fun `commandFor falls back to generic command when specific missing`() {
        val script = TemplateScript(name = "install", command = "generic", aptCommand = "apt install")
        assertEquals("generic", script.commandFor(WorkspacePackageManager.APK))
    }

    @Test
    fun `detectPackageManager finds apt and apk markers`() {
        val manager = buildManager(RecordingRunner(), newTempDir())
        manager.ensureWorkspace("aptws")
        File(manager.linuxDir("aptws"), "usr/bin").mkdirs()
        File(manager.linuxDir("aptws"), "usr/bin/apt-get").createNewFile()

        manager.ensureWorkspace("apkws")
        File(manager.linuxDir("apkws"), "sbin").mkdirs()
        File(manager.linuxDir("apkws"), "sbin/apk").createNewFile()

        manager.ensureWorkspace("empty")

        val applier = TemplateApplier(manager)
        assertEquals(WorkspacePackageManager.APT, applier.detectPackageManager("aptws"))
        assertEquals(WorkspacePackageManager.APK, applier.detectPackageManager("apkws"))
        assertEquals(WorkspacePackageManager.UNKNOWN, applier.detectPackageManager("empty"))
    }

    @Test
    fun `apply uses package manager specific command`() {
        val runner = RecordingRunner()
        val manager = buildManager(runner, newTempDir())
        manager.ensureWorkspace("ws")
        File(manager.linuxDir("ws"), "usr/bin").mkdirs()
        File(manager.linuxDir("ws"), "usr/bin/apt-get").createNewFile()
        val applier = TemplateApplier(manager)

        val template = WorkspaceTemplate(
            id = "t4",
            name = "Test",
            description = "",
            scripts = listOf(
                TemplateScript(
                    name = "install",
                    command = "generic",
                    aptCommand = "apt install",
                    apkCommand = "apk install",
                ),
            ),
        )

        applier.apply("ws", template)
        assertEquals(listOf("apt install"), runner.executed)
    }

    @Test
    fun `builtin templates are well formed`() {
        val ids = BuiltinTemplates.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(BuiltinTemplates.ALL.all { it.builtin })
        assertTrue(BuiltinTemplates.ALL.all { it.name.isNotBlank() })

        val python = BuiltinTemplates.byId("builtin-python")
        assertTrue(python != null)
        assertFalse(python!!.scripts.isEmpty())
        assertTrue(python.scripts.all { it.aptCommand != null || it.command.isNotBlank() })

        assertEquals(null, BuiltinTemplates.byId("missing"))
    }

    @Test
    fun `template serialization round trip keeps scripts`() {
        val template = WorkspaceTemplate(
            id = "custom-1",
            name = "My env",
            description = "desc",
            rootfsUrl = "https://example.com/rootfs.tar.gz",
            scripts = listOf(
                TemplateScript(name = "s1", command = "echo hi", timeoutMs = 1234),
            ),
            createdAt = 42L,
        )
        val json = kotlinx.serialization.json.Json.encodeToString(WorkspaceTemplate.serializer(), template)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(WorkspaceTemplate.serializer(), json)
        assertEquals(template, decoded)
    }
}
