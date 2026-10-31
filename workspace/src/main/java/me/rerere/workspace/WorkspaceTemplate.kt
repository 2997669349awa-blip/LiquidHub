package me.rerere.workspace

import kotlinx.serialization.Serializable

/**
 * A single named step of a [WorkspaceTemplate]. Executed inside the rootfs after
 * the rootfs itself has been installed and patched.
 */
@Serializable
data class TemplateScript(
    val name: String,
    /** Fallback command used when no package-manager specific command matches. */
    val command: String,
    val apkCommand: String? = null,
    val aptCommand: String? = null,
    val pacmanCommand: String? = null,
    val timeoutMs: Long = 10 * 60_000L,
) {
    fun commandFor(manager: WorkspacePackageManager): String = when (manager) {
        WorkspacePackageManager.APK -> apkCommand ?: command
        WorkspacePackageManager.APT -> aptCommand ?: command
        WorkspacePackageManager.PACMAN -> pacmanCommand ?: command
        WorkspacePackageManager.UNKNOWN -> command
    }
}

/**
 * Declarative description of a ready-to-use environment: a rootfs source plus an
 * ordered list of post-install scripts. [rootfsUrl] == null means "use the
 * default rootfs preset for the current ABI".
 */
@Serializable
data class WorkspaceTemplate(
    val id: String,
    val name: String,
    val description: String,
    val icon: String = "Terminal",
    val builtin: Boolean = false,
    val rootfsUrl: String? = null,
    val scripts: List<TemplateScript> = emptyList(),
    val createdAt: Long = 0L,
)

enum class WorkspacePackageManager {
    APK,
    APT,
    PACMAN,
    UNKNOWN,
}

sealed interface TemplateApplyResult {
    data object Success : TemplateApplyResult

    data class Failed(
        val script: TemplateScript,
        val scriptIndex: Int,
        val output: String,
        val exitCode: Int,
    ) : TemplateApplyResult
}

/** Progress snapshot while a template is being applied, for UI + notification. */
data class TemplateApplyProgress(
    val templateId: String,
    val templateName: String,
    val scriptIndex: Int,
    val totalScripts: Int,
    val currentScriptName: String,
)
