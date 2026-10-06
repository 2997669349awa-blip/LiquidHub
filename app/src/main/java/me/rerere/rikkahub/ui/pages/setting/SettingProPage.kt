// Modified by AI Hello World on 2026-10-06.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 设置 > 其他 > LikkaHub Pro：下载 Pro 插件后解锁实验性高级能力。

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.theme.CustomColors
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.compose.koinInject

private const val PRO_PLUGIN_URL = "https://2997669349awa-blip.github.io/LiquidHub/pro.json"

@Composable
fun SettingProPage() {
    val settingsStore = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    var downloading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun update(fn: (Settings) -> Settings) {
        scope.launch { settingsStore.update(fn) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("LikkaHub Pro") },
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
                text = "下载 LikkaHub Pro 插件后，可开启更多实验性高级能力。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CardGroup {
                item(
                    onClick = {
                        if (!settings.proDownloaded && !downloading) {
                            downloading = true
                            message = null
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val client = OkHttpClient()
                                        val request = Request.Builder().url(PRO_PLUGIN_URL).get().build()
                                        client.newCall(request).execute().use { it.isSuccessful }
                                    }.getOrDefault(false)
                                }
                                downloading = false
                                if (ok) {
                                    update { it.copy(proDownloaded = true) }
                                    message = "插件已安装，已解锁 Pro 功能"
                                } else {
                                    message = "下载失败，请检查网络后重试"
                                }
                            }
                        }
                    },
                    headlineContent = {
                        Text(if (settings.proDownloaded) "LikkaHub Pro 已安装" else "下载 LikkaHub Pro 插件")
                    },
                    supportingContent = {
                        Text(if (settings.proDownloaded) "可开启下方高级功能" else "从官方站点下载并启用")
                    },
                    trailingContent = if (downloading) {
                        { CircularProgressIndicator(modifier = Modifier.size(20.dp)) }
                    } else {
                        null
                    },
                )
            }

            if (settings.proDownloaded) {
                CardGroup(title = { Text("高级功能") }) {
                    ProToggle(
                        label = "操控 Pro",
                        desc = "让 AI 更彻底地操控手机，减少中途被打断",
                        checked = settings.proControl,
                    ) { update { s -> s.copy(proControl = it) } }
                    ProToggle(
                        label = "思考 Pro",
                        desc = "让模型以最高思考程度生成",
                        checked = settings.proThinking,
                    ) { update { s -> s.copy(proThinking = it) } }
                    ProToggle(
                        label = "沙盒 Pro",
                        desc = "工作区最高权限并开放外网",
                        checked = settings.proSandbox,
                    ) { update { s -> s.copy(proSandbox = it) } }
                    ProToggle(
                        label = "定时 Pro",
                        desc = "让 AI 在指定时间自动发起任务",
                        checked = settings.proSchedule,
                    ) { update { s -> s.copy(proSchedule = it) } }
                    ProToggle(
                        label = "歌词 Pro",
                        desc = "在桌面悬浮显示实时歌词",
                        checked = settings.proLyrics,
                    ) { update { s -> s.copy(proLyrics = it) } }
                }
            }

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun CardGroupScope.ProToggle(
    label: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    item(
        headlineContent = { Text(label) },
        supportingContent = { Text(desc) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
