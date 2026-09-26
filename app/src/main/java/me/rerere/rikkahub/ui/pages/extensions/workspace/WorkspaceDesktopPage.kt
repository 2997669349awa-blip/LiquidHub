// Modified by AI Hello World on 2026-09-25.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.

package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.data.workspace.WorkspaceDesktopManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private fun stageLabel(stage: DesktopStage): String = when (stage) {
    DesktopStage.IDLE -> "空闲"
    DesktopStage.PREPARING -> "处理中"
    DesktopStage.UPDATING -> "更新软件源"
    DesktopStage.DOWNLOADING -> "下载中"
    DesktopStage.UNPACKING -> "安装中"
    DesktopStage.DONE -> "完成"
}

@Composable
fun WorkspaceDesktopPage(id: String) {
    val vm: WorkspaceDesktopVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("远程桌面") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = null)
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (state.busy) {
                val progress = state.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(enabled = !state.busy && state.shellReady != false, onClick = { vm.install() }) {
                    Text("安装环境")
                }
                OutlinedButton(
                    enabled = !state.busy && state.shellReady != false,
                    onClick = { vm.reinstall() },
                ) {
                    Text("重装")
                }
                Button(
                    enabled = !state.busy && state.installed == true && state.shellReady != false,
                    onClick = { vm.start() },
                ) {
                    Text("启动")
                }
                OutlinedButton(enabled = !state.busy && state.shellReady != false, onClick = { vm.stop() }) {
                    Text("停止")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = when {
                        state.busy -> state.progressText ?: stageLabel(state.stage)
                        state.running -> "运行中"
                        else -> "已停止"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.shellReady == false) {
                Text(
                    text = "该工作区还没有可用的系统（Rootfs），请先到「基本」标签页安装后再回来。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            if (state.running && state.webReady == true) {
                WebView(
                    state = rememberWebViewState(WorkspaceDesktopManager.NOVNC_URL),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.running && state.webReady != true) {
                        Text(
                            text = "桌面(X)已启动，但网页桌面服务(noVNC/websockify)没起来，所以这个网页打不开。" +
                                "点「查看服务日志」能看到具体原因。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("网页桌面（noVNC）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "首次使用先点「安装环境」（安装 Fluxbox、Chromium、VNC 与 noVNC，Alpine 工作区最省流）。" +
                            "完成后点「启动」，这里会显示可直接操作的桌面网页。" +
                            "AI 也能对同一桌面截图、点击、输入；遇到登录等需要人操作的情况，你可以在本页面直接接管。" +
                            "装坏了可以点「重装」强制重来。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InstallLog(state = state, onLoadLogs = { vm.loadLogs() })
                }
            }
        }
    }
}

@Composable
private fun InstallLog(state: WorkspaceDesktopState, onLoadLogs: () -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(state.log) {
        scroll.scrollTo(scroll.maxValue)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (state.busy) "安装日志 · ${state.progressText ?: stageLabel(state.stage)}" else "安装日志",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onLoadLogs) { Text("查看服务日志") }
        }
        Spacer(Modifier.height(4.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                if (state.log.isBlank()) {
                    Text(
                        text = "还没有日志。点「安装环境」后会实时显示每一步。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = state.log,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
