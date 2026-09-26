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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.data.workspace.WorkspaceDesktopManager
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.components.nav.BackButton
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
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
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
                OutlinedButton(enabled = !state.busy && state.shellReady != false, onClick = { vm.reinstall() }) {
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
            if (state.running) {
                DesktopSurface()
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("工作区桌面", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "首次使用先点「安装环境」（Fluxbox + VNC，Alpine 工作区最省流），完成后点「启动」，" +
                            "桌面的画面会直接显示在这里（App 内置 VNC 客户端，不经过网页）。" +
                            "AI 也能对同一桌面截图、点击、输入。装坏了可以点「重装」。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "构建版本：${BuildConfig.BUILD_STAMP}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InstallLog(state = state, onLoadLogs = { vm.loadLogs() })
                }
            }
        }
    }
}

@Composable
private fun DesktopSurface() {
    var vncRef by remember { mutableStateOf<VncView?>(null) }
    var status by remember { mutableStateOf("正在连接桌面…") }
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                VncView(ctx).apply {
                    listener = object : VncView.Listener {
                        override fun onState(s: String) {
                            status = s
                        }

                        override fun onError(message: String) {
                            status = "连接失败：$message"
                        }
                    }
                    connect()
                }.also { vncRef = it }
            },
            onRelease = {
                it.disconnect()
                if (vncRef === it) vncRef = null
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("输入文字（回车发送）") },
            )
            Button(onClick = {
                vncRef?.sendText(input + "\n")
                input = ""
            }) {
                Text("发送")
            }
            TextButton(onClick = {
                vncRef?.disconnect()
                vncRef?.connect()
            }) {
                Text("重连")
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
