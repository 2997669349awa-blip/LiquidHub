package me.rerere.workspace

import java.io.File
import java.nio.file.Files

/**
 * Executes the post-install scripts of a [WorkspaceTemplate] inside a workspace
 * rootfs, in order, stopping at the first failure.
 *
 * Blocking by design: callers are expected to wrap [apply] in `runInterruptible`
 * (mirroring [WorkspaceManager.executeCommand] usage) so cancellation kills the
 * running child process.
 */
class TemplateApplier(
    private val manager: WorkspaceManager,
) {

    fun detectPackageManager(root: String): WorkspacePackageManager {
        val linux = manager.linuxDir(root)
        val candidates = listOf(
            WorkspacePackageManager.APT to "usr/bin/apt-get",
            WorkspacePackageManager.APK to "sbin/apk",
            WorkspacePackageManager.PACMAN to "usr/bin/pacman",
        )
        for ((manager1, relative) in candidates) {
            val file = File(linux, relative)
            if (file.isFile || Files.isSymbolicLink(file.toPath())) return manager1
        }
        return WorkspacePackageManager.UNKNOWN
    }

    fun apply(
        root: String,
        template: WorkspaceTemplate,
        cwd: String = "",
        onProgress: ((TemplateApplyProgress) -> Unit)? = null,
        onOutput: ((String) -> Unit)? = null,
    ): TemplateApplyResult {
        val packageManager = detectPackageManager(root)
        val scripts = template.scripts
        scripts.forEachIndexed { index, script ->
            onProgress?.invoke(
                TemplateApplyProgress(
                    templateId = template.id,
                    templateName = template.name,
                    scriptIndex = index,
                    totalScripts = scripts.size,
                    currentScriptName = script.name,
                ),
            )
            val command = script.commandFor(packageManager)
            val output = StringBuilder()
            val combinedOutput: ((String) -> Unit)? = { chunk ->
                onOutput?.invoke(chunk)
                synchronized(output) {
                    if (output.length < MAX_FAILURE_LOG_CHARS) output.append(chunk)
                }
            }
            val result = try {
                manager.executeCommand(
                    root = root,
                    command = command,
                    cwd = cwd,
                    timeoutMillis = script.timeoutMs,
                    onOutput = combinedOutput,
                )
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                return TemplateApplyResult.Failed(
                    script = script,
                    scriptIndex = index,
                    output = "Failed to launch command: ${e.message}",
                    exitCode = -1,
                )
            }
            if (result.exitCode != 0 || result.timedOut) {
                val tail = synchronized(output) { output.toString() }
                    .takeLast(MAX_FAILURE_LOG_CHARS)
                val reason = if (result.timedOut) "command timed out" else "exit code ${result.exitCode}"
                return TemplateApplyResult.Failed(
                    script = script,
                    scriptIndex = index,
                    output = tail.ifBlank { reason },
                    exitCode = result.exitCode,
                )
            }
        }
        return TemplateApplyResult.Success
    }

    companion object {
        private const val MAX_FAILURE_LOG_CHARS = 32 * 1024
    }
}
