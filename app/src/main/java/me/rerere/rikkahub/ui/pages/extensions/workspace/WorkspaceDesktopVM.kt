// Modified by AI Hello World on 2026-09-25.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.

package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.workspace.WorkspaceDesktopManager
import me.rerere.workspace.WorkspaceShellStatus

data class WorkspaceDesktopState(
    val busy: Boolean = false,
    val running: Boolean = false,
    val installed: Boolean? = null,
    val shellReady: Boolean? = null,
    val log: String = "",
    val error: String? = null,
)

class WorkspaceDesktopVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val desktopManager: WorkspaceDesktopManager,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDesktopState())
    val state = _state.asStateFlow()

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
            _state.update {
                it.copy(
                    shellReady = true,
                    running = output.contains("RUNNING"),
                    installed = output.contains("XVFB_YES"),
                )
            }
        }
    }

    fun install() = runAction("install", timeoutMillis = 30 * 60_000L)
    fun start() = runAction("start", timeoutMillis = 60_000L)
    fun stop() = runAction("stop", timeoutMillis = 60_000L)

    private fun runAction(action: String, timeoutMillis: Long) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                desktopManager.ensureScript(id)
                if (action == "start" || action == "stop") {
                    desktopManager.runAction(id, action)
                    delay(2_000)
                } else {
                    val result = repository.executeCommand(
                        id = id,
                        command = "if [ -f /workspace/${WorkspaceDesktopManager.SCRIPT_PATH} ]; " +
                            "then sh /workspace/${WorkspaceDesktopManager.SCRIPT_PATH} $action; " +
                            "else echo SCRIPT_MISSING; fi",
                        timeoutMillis = timeoutMillis,
                    )
                    _state.update { current ->
                        current.copy(log = (result.stdout + "\n" + result.stderr).trim())
                    }
                    if (result.exitCode != 0) {
                        _state.update { it.copy(error = "命令退出码 ${result.exitCode}") }
                    }
                }
            } catch (error: Throwable) {
                _state.update { it.copy(error = error.message ?: "执行失败") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
            refresh()
        }
    }

    private companion object {
        const val DESKTOP_PROBE =
            "sh /workspace/${WorkspaceDesktopManager.SCRIPT_PATH} status 2>/dev/null || echo STOPPED; " +
                "command -v Xvfb >/dev/null 2>&1 && echo XVFB_YES || echo XVFB_NO"
    }
}
