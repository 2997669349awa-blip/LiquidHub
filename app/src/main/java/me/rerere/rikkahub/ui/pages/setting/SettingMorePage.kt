// Modified by AI Hello World on 2026-10-06.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 设置 > 其他 > 更多功能：DSH 服务与插件市场。

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.koin.compose.koinInject

private const val PLUGINS_URL = "https://2997669349awa-blip.github.io/LiquidHub/plugins.json"

@Composable
fun SettingMorePage() {
    val settingsStore = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    var plugins by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(Unit) {
        plugins = withContext(Dispatchers.IO) {
            runCatching {
                val client = OkHttpClient()
                val request = Request.Builder().url(PLUGINS_URL).get().build()
                val body = client.newCall(request).execute().use { it.body?.string().orEmpty() }
                val array = JSONObject(body).optJSONArray("plugins") ?: return@runCatching emptyList()
                (0 until array.length()).map { index ->
                    val obj = array.getJSONObject(index)
                    Triple(obj.optString("name"), obj.optString("type"), obj.optString("description"))
                }
            }.getOrDefault(emptyList())
        }
        loading = false
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("更多功能") },
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
            CardGroup(title = { Text("服务") }) {
                item(
                    headlineContent = { Text("DSH 服务") },
                    supportingContent = { Text("随应用自动启动") },
                    trailingContent = {
                        Switch(
                            checked = settings.dshAutoStart,
                            onCheckedChange = { value ->
                                scope.launch { settingsStore.update { it.copy(dshAutoStart = value) } }
                            },
                        )
                    },
                )
            }

            CardGroup(title = { Text("插件市场") }) {
                when {
                    loading -> item(headlineContent = { Text("加载中...") })
                    plugins.isEmpty() -> item(
                        headlineContent = { Text("暂无插件") },
                        supportingContent = { Text("请检查网络后重试") },
                    )

                    else -> plugins.forEach { (name, type, desc) ->
                        item(
                            headlineContent = { Text(name) },
                            supportingContent = { Text("[$type] $desc") },
                        )
                    }
                }
            }

            Text(
                text = "插件市场中的 DSH 及 AI 插件会在此列出，后续可一键安装。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
