// Modified by AI Hello World on 2026-09-25.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.

package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.workspace.WorkspaceDesktopManager
import me.rerere.workspace.WorkspaceShellStatus
import java.util.Locale

enum class DesktopStage {
    IDLE,
    PREPARING,
    UPDATING,
    DOWNLOADING,
    UNPACKING,
    DONE,
}

data class WorkspaceDesktopState(
    val busy: Boolean = false,
    val running: Boolean = false,
    val installed: Boolean? = null,
    val shellReady: Boolean? = null,
    val stage: DesktopStage = DesktopStage.IDLE,
    val progress: Float? = null,
    val progressText: String? = null,
    val log: String = "",
    val error: String? = null,
    val browser: String = "auto",
    val audioEnabled: Boolean = true,
)

class WorkspaceDesktopVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val desktopManager: WorkspaceDesktopManager,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDesktopState())
    val state = _state.asStateFlow()

    // 实时日志与进度解析状态
    private val logBuilder = StringBuilder()
    private var processedLength = 0
    private var lastPushAt = 0L
    private var totalDownloadBytes = -1L
    private var downloadedBytes = 0L
    private val seenFetched = mutableSetOf<String>()
    private var packageDone = 0
    private var packageTotal = 0
    private var sawUpdate = false
    private var sawDownload = false
    private var sawUnpack = false

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val workspace = runCatching { repository.getById(id) }.getOrNull()
            val ready = workspace?.shellStatus == WorkspaceShellStatus.READY.name
            if (!ready) {
                _state.update {
                    it.copy(shellReady = false, running = false, installed = false)
                }
                return@launch
            }
            val probe = runCatching {
                repository.executeCommand(
                    id = id,
                    command = DESKTOP_PROBE,
                    timeoutMillis = 20_000,
                )
            }.getOrNull()
            val output = probe?.stdout.orEmpty()
            val lines = output.lineSequence().map { it.trim() }.toList()
            val browser = lines.firstOrNull { it.startsWith("BROWSER=") }
                ?.removePrefix("BROWSER=")
                ?.trim()
                .orEmpty()
                .ifBlank { "auto" }
            val audio = lines.firstOrNull { it.startsWith("AUDIO=") }
                ?.removePrefix("AUDIO=")
                ?.trim()
                ?.let { it != "0" } ?: true
            _state.update {
                it.copy(
                    shellReady = true,
                    running = lines.contains("VNC_RUNNING"),
                    installed = lines.contains("XVFB_YES"),
                    browser = browser,
                    audioEnabled = audio,
                )
            }
        }
    }

    fun install() = runAction("install", timeoutMillis = 30 * 60_000L)
    fun reinstall() = runAction("reinstall", timeoutMillis = 30 * 60_000L)
    fun start() = runAction("start", timeoutMillis = 60_000L)
    fun stop() = runAction("stop", timeoutMillis = 60_000L)

    /** bin 为空字符串表示自动选择。 */
    fun setBrowser(bin: String) {
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(
                    id = id,
                    command = "mkdir -p /workspace/.liquidhub && printf '%s' '$bin' > /workspace/.liquidhub/browser",
                    timeoutMillis = 15_000,
                )
            }
            _state.update { it.copy(browser = bin.ifBlank { "auto" }) }
        }
    }

    fun setAudio(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(
                    id = id,
                    command = "mkdir -p /workspace/.liquidhub && printf '%s' '${if (enabled) 1 else 0}' > /workspace/.liquidhub/audio",
                    timeoutMillis = 15_000,
                )
            }
            _state.update { it.copy(audioEnabled = enabled) }
        }
    }

    /** 拉取桌面各服务（Xvfb/x11vnc/websockify/browser）的日志，方便排查网页打不开。 */
    fun loadLogs() {
        viewModelScope.launch {
            val result = runCatching {
                repository.executeCommand(
                    id = id,
                    command = "sh /workspace/${WorkspaceDesktopManager.SCRIPT_PATH} logs",
                    timeoutMillis = 15_000,
                )
            }.getOrNull() ?: return@launch
            val text = (result.stdout + "\n" + result.stderr).trim()
            if (text.isNotBlank()) {
                _state.update { it.copy(log = text.takeLast(MAX_LOG_CHARS)) }
            }
        }
    }

    private fun runAction(action: String, timeoutMillis: Long) {
        if (_state.value.busy) return
        if (action == "install" || action == "reinstall") resetInstallProgress()
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                desktopManager.ensureScript(id)
                if (action == "start" || action == "stop") {
                    desktopManager.runAction(id, action)
                    delay(2_500)
                    // start 的输出走长驻终端会话，拿不到；改拉取服务日志（含 5900 端口/进程状态）
                    if (action == "start") loadLogs()
                } else {
                    appendLog("[${if (action == "reinstall") "重装" else "安装"}环境] 开始，下面是实时输出\n")
                    pushLog()
                    val script = "/workspace/${WorkspaceDesktopManager.SCRIPT_PATH}"
                    // apt/apk 在非 TTY 下会块缓冲输出，用 stdbuf 强制行缓冲，日志才能实时刷新
                    val run = "if command -v stdbuf >/dev/null 2>&1; then " +
                        "stdbuf -oL -eL sh $script $action; else sh $script $action; fi"
                    val command = "if [ -f $script ]; then $run; else echo SCRIPT_MISSING; fi"
                    appendLog("[命令] $command\n")
                    val result = repository.executeCommand(
                        id = id,
                        command = command,
                        timeoutMillis = timeoutMillis,
                        onOutput = ::onOutput,
                    )
                    flushLog(force = true)
                    appendLog(
                        "\n[退出码 ${result.exitCode} · stdout ${result.stdout.length} 字 · stderr ${result.stderr.length} 字]\n"
                    )
                    pushLog()
                    if (result.exitCode != 0) {
                        _state.update { it.copy(error = "安装失败（退出码 ${result.exitCode}），详见下方日志") }
                    } else {
                        _state.update { it.copy(stage = DesktopStage.DONE) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(error = error.message ?: "执行失败") }
                appendLog("\n[异常] ${error.message ?: error::class.java.simpleName}\n")
                pushLog()
            } finally {
                _state.update { it.copy(busy = false) }
            }
            refresh()
        }
    }

    private fun resetInstallProgress() {
        synchronized(logBuilder) { logBuilder.setLength(0) }
        processedLength = 0
        lastPushAt = 0L
        totalDownloadBytes = -1L
        downloadedBytes = 0L
        seenFetched.clear()
        packageDone = 0
        packageTotal = 0
        sawUpdate = false
        sawDownload = false
        sawUnpack = false
        _state.update {
            it.copy(
                log = "",
                progress = null,
                progressText = null,
                stage = DesktopStage.PREPARING,
            )
        }
    }

    // 采集线程回调：只做字符串拼接与新行解析，节流后推给 UI
    private fun onOutput(chunk: String) {
        synchronized(logBuilder) { logBuilder.append(chunk) }
        processNewLines()
        flushLog()
    }

    private fun appendLog(text: String) {
        synchronized(logBuilder) { logBuilder.append(text) }
        flushLog()
    }

    private fun processNewLines() {
        val start: Int
        val slice: String
        synchronized(logBuilder) {
            start = processedLength
            if (start >= logBuilder.length) return
            slice = logBuilder.substring(start)
        }
        val lastNl = slice.lastIndexOf('\n')
        if (lastNl < 0) return
        val complete = slice.substring(0, lastNl + 1)
        processedLength = start + lastNl + 1
        complete.lineSequence().forEach(::analyzeLine)
    }

    private fun analyzeLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return

        APT_NEED.find(line)?.let { match ->
            val bytes = parseSize(match.groupValues[1], match.groupValues[2])
            if (bytes > 0) {
                totalDownloadBytes = bytes
                sawDownload = true
            }
        }
        APT_GET.find(line)?.let { match ->
            val index = match.groupValues[1]
            if (seenFetched.add(index)) {
                downloadedBytes += parseSize(match.groupValues[2], match.groupValues[3]).coerceAtLeast(0L)
            }
            sawDownload = true
        }
        COUNT.find(line)?.let { match ->
            packageDone = match.groupValues[1].toIntOrNull() ?: packageDone
            packageTotal = match.groupValues[2].toIntOrNull() ?: packageTotal
        }
        when {
            line.startsWith("Unpacking") ||
                line.startsWith("Setting up") ||
                line.contains("Preparing to unpack") ||
                line.contains("Installing") -> sawUnpack = true
            line.startsWith("Hit:") ||
                line.startsWith("Get:") ||
                line.startsWith("Ign:") ||
                line.startsWith("Err:") ||
                line.contains("apt-get update") ||
                line.contains("Reading package lists") ||
                line.contains("apk add") ||
                line.contains("pacman -Sy") -> sawUpdate = true
        }
    }

    private fun flushLog(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPushAt < 120) return
        lastPushAt = now
        pushLog()
    }

    private fun pushLog() {
        val text = synchronized(logBuilder) { logBuilder.toString() }
        val stage = when {
            sawUnpack -> DesktopStage.UNPACKING
            sawDownload -> DesktopStage.DOWNLOADING
            sawUpdate -> DesktopStage.UPDATING
            text.isNotBlank() -> DesktopStage.PREPARING
            else -> _state.value.stage
        }
        val progress: Float? = when {
            totalDownloadBytes > 0 ->
                (downloadedBytes.toDouble() / totalDownloadBytes).toFloat().coerceIn(0f, 1f)
            packageTotal > 0 -> (packageDone.toDouble() / packageTotal).toFloat().coerceIn(0f, 1f)
            else -> null
        }
        val progressText: String? = when {
            totalDownloadBytes > 0 ->
                "下载中 ${(progress!! * 100).toInt()}% (${formatSize(downloadedBytes)}/${formatSize(totalDownloadBytes)})"
            packageTotal > 0 -> "安装中 $packageDone/$packageTotal"
            else -> null
        }
        _state.update {
            it.copy(
                stage = stage,
                progress = progress,
                progressText = progressText,
                log = text.takeLast(MAX_LOG_CHARS),
            )
        }
    }

    private companion object {
        const val MAX_LOG_CHARS = 60_000
        const val DESKTOP_PROBE =
            "sh /workspace/${WorkspaceDesktopManager.SCRIPT_PATH} status 2>/dev/null; " +
                "command -v Xvfb >/dev/null 2>&1 && echo XVFB_YES || echo XVFB_NO; " +
                "echo BROWSER=$(cat /workspace/.liquidhub/browser 2>/dev/null); " +
                "echo AUDIO=$(cat /workspace/.liquidhub/audio 2>/dev/null)"
        val APT_NEED = Regex("Need to get ([\\d.,]+)\\s*([kKmMgG]?B)", RegexOption.IGNORE_CASE)
        val APT_GET = Regex("Get:(\\d+)\\s+\\S+.*?\\[([\\d.,]+)\\s*([kKmMgG]?B)\\]", RegexOption.IGNORE_CASE)
        val COUNT = Regex("\\((\\d+)\\s*/\\s*(\\d+)\\)")

        fun parseSize(number: String, unit: String): Long {
            val value = number.replace(",", "").toDoubleOrNull() ?: return -1
            val factor = when (unit.uppercase(Locale.US)) {
                "KB" -> 1024.0
                "MB" -> 1024.0 * 1024
                "GB" -> 1024.0 * 1024 * 1024
                else -> 1.0
            }
            return (value * factor).toLong()
        }

        fun formatSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
            bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
            bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
