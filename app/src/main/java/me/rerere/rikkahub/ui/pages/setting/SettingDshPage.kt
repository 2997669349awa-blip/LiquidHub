// Modified by AI Hello World on 2026-10-07.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 设置 > 其他 > DSH 服务：在工作区安装并启动 DeepSeek Harness，内嵌打开其 Web UI。

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

private const val DSH_URL = "http://127.0.0.1:3080"

private val DSH_INSTALL_SCRIPT = """
set -e
echo "== 检查 Node =="
if ! command -v node >/dev/null 2>&1; then
  echo "未检测到 Node，请先在工作区安装 Node.js 22.19+ / 24+";
  exit 1
fi
node -v
echo "== 安装 DeepSeek Harness =="
npm i -g @deepseek-ai/dsh
echo "== 安装必装插件 dsh-web-mobile =="
dsh plugin --profile web add dsh-web-mobile || true
echo "== 启动 DSH Web =="
nohup dsh web > /tmp/dsh.log 2>&1 &
sleep 2
echo "DSH 已在后台启动，日志：/tmp/dsh.log"
""".trimIndent()

@Composable
fun SettingDshPage() {
    val workspaceRepository = koinInject<WorkspaceRepository>()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val workspaces by workspaceRepository.listFlow()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun runInstall() {
        val workspace = workspaces.firstOrNull()
        if (workspace == null) {
            output = "没有可用的工作区，请先在 设置 > 工作区 里创建。"
            return
        }
        busy = true
        output = "正在安装并启动 DSH（可能需要几分钟）..."
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    workspaceRepository.executeCommand(
                        id = workspace.id,
                        command = DSH_INSTALL_SCRIPT,
                        timeoutMillis = 10 * 60 * 1000L,
                    )
                }
            }.getOrElse { null }
            busy = false
            output = when {
                result == null -> "执行失败，请检查工作区是否就绪。"
                result.exitCode == 0 -> "完成。\n${result.stdout.takeLast(2000)}"
                else -> "退出码 ${result.exitCode}\n${result.stdout.takeLast(2000)}\n${result.stderr.takeLast(1000)}"
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("DSH 服务") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(innerPadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "DeepSeek Harness（DSH）是 DeepSeek 开源的 Agent 运行时框架。此处会在你的工作区里安装并启动它，并自动装上移动端适配插件 dsh-web-mobile，然后用内嵌页面打开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CardGroup(title = { Text("状态") }) {
                item(
                    headlineContent = { Text(if (busy) "安装中..." else "未运行或未安装") },
                    supportingContent = { Text("工作区：${workspaces.firstOrNull()?.name ?: "无"}") },
                )
            }

            Button(
                onClick = { runInstall() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("安装并启动 DSH")
            }

            OutlinedButton(
                onClick = { navController.navigate(Screen.WebView(url = DSH_URL, contentId = "dsh")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开 DSH 界面")
            }

            if (output.isNotBlank()) {
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
