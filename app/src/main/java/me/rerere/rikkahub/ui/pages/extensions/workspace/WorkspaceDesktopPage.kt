// Modified by AI Hello World on 2026-09-25.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.

package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cursor01
import me.rerere.hugeicons.stroke.Cursor02
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.workspace.WorkspaceDesktopManager
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceDesktopPage(id: String) {
    val vm: WorkspaceDesktopVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    var entered by remember { mutableStateOf(false) }

    // 进入桌面：全屏显示
    if (entered && state.running) {
        FullScreenDesktop(audioEnabled = state.audioEnabled, onExit = { entered = false })
        return
    }

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
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
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
                Button(
                    enabled = !state.busy && state.running,
                    onClick = { entered = true },
                ) {
                    Text("进入桌面")
                }
                OutlinedButton(enabled = !state.busy && state.shellReady != false, onClick = { vm.stop() }) {
                    Text("停止")
                }
            }
            Text(
                text = when {
                    state.busy -> "状态：${state.progressText ?: stageLabel(state.stage)}"
                    state.running -> "状态：桌面已启动（x11vnc 运行中，可进入）"
                    else -> "状态：已停止"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
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

            if (!state.running) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("使用步骤", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "1) 安装环境 → 2) 启动（会常驻保活）→ 3) 进入桌面（全屏，可直接触控）。\n" +
                            "桌面里底部工具栏可以按下左键/右键、切换「触屏/触摸板」、发送文字。装坏了点「重装」。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "构建版本：${BuildConfig.BUILD_STAMP}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("浏览器（AI 打开网页时使用）", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "auto" to "自动",
                            "firefox" to "火狐",
                            "chromium" to "Chrome",
                            "falkon" to "Falkon",
                        ).forEach { (bin, label) ->
                            FilterChip(
                                selected = state.browser == bin,
                                onClick = { vm.setBrowser(if (bin == "auto") "" else bin) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text("分辨率（越高越清晰、也越吃性能）", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "1280x720" to "720p 流畅",
                            "1600x900" to "900p",
                            "1920x1080" to "1080p 清晰",
                        ).forEach { (res, label) ->
                            FilterChip(
                                selected = state.resolution == res,
                                onClick = { vm.setResolution(res) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("播放桌面声音", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                "实验性",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Switch(checked = state.audioEnabled, onCheckedChange = { vm.setAudio(it) })
                    }
                    InstallLog(state = state, onLoadLogs = { vm.loadLogs() })
                }
            }
        }
    }
}

/** 全屏桌面：VNC 画面 + 底部操作栏。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FullScreenDesktop(audioEnabled: Boolean, onExit: () -> Unit) {
    var vncRef by remember { mutableStateOf<VncView?>(null) }
    var status by remember { mutableStateOf("正在连接桌面…") }
    var touchpad by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var showInput by remember { mutableStateOf(false) }

    val audioPlayer = remember { PulseAudioPlayer() }
    var audioState by remember { mutableStateOf("") }
    LaunchedEffect(audioEnabled) {
        audioPlayer.listener = PulseAudioPlayer.Listener { audioState = it }
        if (audioEnabled) audioPlayer.start() else audioPlayer.stop()
    }
    DisposableEffect(Unit) {
        onDispose { audioPlayer.stop() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VncView(ctx).apply {
                    listener = object : VncView.Listener {
                        override fun onState(state: String) {
                            status = state
                        }

                        override fun onError(message: String) {
                            status = message
                        }
                    }
                    touchpadMode = touchpad
                    connect()
                }.also { vncRef = it }
            },
            update = { it.touchpadMode = touchpad },
            onRelease = {
                it.disconnect()
                if (vncRef === it) vncRef = null
            },
        )

        Text(
            text = if (audioState.isBlank()) status else "$status\n$audioState",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color(0x99000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )

        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (showInput) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xE6000000))
                        .padding(8.dp),
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
                    }) { Text("发送") }
                }
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { vncRef?.leftClick() }) {
                    Icon(HugeIcons.Cursor01, contentDescription = "左键", tint = Color.White)
                }
                IconButton(onClick = { vncRef?.rightClick() }) {
                    Icon(HugeIcons.Cursor02, contentDescription = "右键", tint = Color.White)
                }
                TextButton(onClick = { touchpad = !touchpad }) {
                    Text(if (touchpad) "触摸板" else "触屏", color = Color.White)
                }
                TextButton(onClick = { showInput = !showInput }) {
                    Text("输入", color = Color.White)
                }
                TextButton(onClick = { vncRef?.sendText("\u000d") }) {
                    Text("回车", color = Color.White)
                }
                TextButton(onClick = onExit) {
                    Text("退出", color = Color.White)
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
                text = if (state.busy) "日志 · ${state.progressText ?: stageLabel(state.stage)}" else "日志",
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
