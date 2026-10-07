// Modified by AI Hello World on 2026-10-07.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// DSH 安装/启动任务：应用级常驻，切换页面或退出页面也不会中断，日志实时输出。

package me.rerere.rikkahub.data.dsh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.WorkspaceRepository

enum class DshState { IDLE, INSTALLING, RUNNING, FAILED }

object DshManager {
    // 应用级作用域：不随页面销毁而取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(DshState.IDLE)
    val state: StateFlow<DshState> = _state.asStateFlow()

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()

    @Volatile
    var lastWorkspaceId: String? = null
        private set

    fun clearLog() {
        _log.value = ""
    }

    fun installAndStart(repo: WorkspaceRepository, workspaceId: String) {
        if (_state.value == DshState.INSTALLING) return
        lastWorkspaceId = workspaceId
        _state.value = DshState.INSTALLING
        clearLog()
        append("开始安装并启动 DSH ...\n")
        scope.launch {
            val result = runCatching {
                repo.executeCommand(
                    id = workspaceId,
                    command = DSH_SCRIPT,
                    timeoutMillis = 30 * 60 * 1000L,
                    onOutput = { chunk -> append(chunk) },
                )
            }.getOrNull()
            when {
                result == null -> {
                    _state.value = DshState.FAILED
                    append("\n执行失败（工作区未就绪或进程被中断）\n")
                }

                result.exitCode == 0 -> {
                    _state.value = DshState.RUNNING
                    append("\n完成，退出码 0。DSH 已在后台启动。\n")
                }

                else -> {
                    _state.value = DshState.FAILED
                    append(
                        "\n失败，退出码 ${result.exitCode}\n" +
                            result.stderr.takeLast(2000) + "\n"
                    )
                }
            }
        }
    }

    private fun append(text: String) {
        _log.value = (_log.value + text).takeLast(30000)
    }
}

private val DSH_SCRIPT = """
echo "== 环境 =="
export HOME=/root
echo "PATH=${'$'}PATH"
if ! command -v node >/dev/null 2>&1; then
  echo "未检测到 Node。请先在工作区安装 Node.js 22.19+ 或 24+。"
  exit 2
fi
node -v
if command -v npm >/dev/null 2>&1; then
  echo "== 使用国内镜像加速 =="
  npm config set registry https://registry.npmmirror.com || true
fi
echo "== 安装 DeepSeek Harness =="
npm i -g @deepseek-ai/dsh --loglevel=info
echo "== 安装必装插件 dsh-web-mobile =="
dsh plugin --profile web add dsh-web-mobile || echo "插件安装可能失败，稍后可重试"
echo "== 启动 DSH Web =="
nohup dsh web > /tmp/dsh.log 2>&1 &
sleep 3
echo "== 最近日志 =="
tail -n 40 /tmp/dsh.log || true
echo "== 完成 =="
""".trimIndent()
