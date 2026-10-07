// Modified by AI Hello World on 2026-10-07.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 设置 > 其他 > DSH 服务：在工作区安装并启动 DeepSeek Harness，内嵌打开其 Web UI。

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.dsh.DshManager
import me.rerere.rikkahub.data.dsh.DshState
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

private const val DSH_URL = "http://127.0.0.1:3080"

@Composable
fun SettingDshPage() {
    val workspaceRepository = koinInject<WorkspaceRepository>()
    val navController = LocalNavController.current
    val workspaces by workspaceRepository.listFlow()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val state by DshManager.state.collectAsStateWithLifecycle()
    val log by DshManager.log.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val logScroll = rememberScrollState()

    val statusText = when (state) {
        DshState.IDLE -> "未开始"
        DshState.INSTALLING -> "安装中（可离开本页，后台继续）"
        DshState.RUNNING -> "已启动"
        DshState.FAILED -> "失败"
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
                text = "DeepSeek Harness（DSH）是 DeepSeek 开源的 Agent 运行时框架。这里会在工作区里安装并启动它，自动装移动端适配插件 dsh-web-mobile，再用内嵌页面打开。任务在后台常驻，切到别的页面也会继续，日志实时刷新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CardGroup(title = { Text("状态") }) {
                item(
                    headlineContent = { Text(statusText) },
                    supportingContent = {
                        Text("工作区：${workspaces.firstOrNull()?.name ?: "无（请先创建工作区）"}")
                    },
                )
            }

            Button(
                onClick = {
                    workspaces.firstOrNull()?.let { workspace ->
                        DshManager.installAndStart(workspaceRepository, workspace.id)
                    }
                },
                enabled = state != DshState.INSTALLING && workspaces.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state == DshState.INSTALLING) "安装中..." else "安装并启动 DSH")
            }

            OutlinedButton(
                onClick = {
                    navController.navigate(Screen.WebView(url = DSH_URL, contentId = "dsh"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开 DSH 界面")
            }

            Text("实时日志", style = MaterialTheme.typography.titleSmall)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 360.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
                    .verticalScroll(logScroll),
            ) {
                Text(
                    text = log.ifBlank { "（暂无日志，点上面的按钮开始）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
