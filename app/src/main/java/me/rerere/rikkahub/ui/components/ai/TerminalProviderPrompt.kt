// Modified by AI Hello World on 2026-09-26.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// One-time prompt that imports the models found in the terminal likkahub local AI.

package me.rerere.rikkahub.ui.components.ai

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.local.LocalAiScanner
import me.rerere.rikkahub.data.datastore.LOCAL_AI_PROVIDER_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

/**
 * 进入 App 时探测终端里的 likkahub 本地AI：
 * 扫到模型就提示「终端中有 N 个模型，是否添加为提供商」，模型数为 0 或已处理过则不打扰。
 */
@Composable
fun TerminalProviderPrompt(
    settings: Settings,
    settingsStore: SettingsStore,
) {
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    var prompted by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun markHandled() {
        scope.launch {
            settingsStore.update { it.copy(terminalProviderPromptHandled = true) }
        }
    }

    LaunchedEffect(settings.init, settings.terminalProviderPromptHandled) {
        if (settings.init || settings.terminalProviderPromptHandled) return@LaunchedEffect
        if (scanned || found.isNotEmpty()) return@LaunchedEffect
        scanned = true
        // 让冷启动先完成，再做一次很轻的本地探测
        delay(1200)
        val alreadyHasModels = settings.providers
            .firstOrNull { it.id == LOCAL_AI_PROVIDER_ID }
            ?.models
            ?.isNotEmpty() == true
        if (alreadyHasModels) return@LaunchedEffect
        val models = LocalAiScanner.listModels()
        if (models.isNotEmpty()) {
            found = models
            prompted = true
        }
    }

    if (prompted && found.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                prompted = false
                markHandled()
            },
            title = { Text("发现终端本地AI") },
            text = {
                Text("终端中有 ${found.size} 个模型，是否添加为提供商并拉取？")
            },
            confirmButton = {
                TextButton(onClick = {
                    prompted = false
                    val names = found
                    scope.launch {
                        settingsStore.update { current ->
                            val updated = current.providers.map { provider ->
                                if (provider.id == LOCAL_AI_PROVIDER_ID && provider is ProviderSetting.OpenAI) {
                                    val existing = provider.models.map { it.modelId }.toSet()
                                    val merged = provider.models + names
                                        .filterNot { it in existing }
                                        .map { Model(modelId = it, displayName = it, id = Uuid.random()) }
                                    provider.copy(enabled = true, models = merged)
                                } else {
                                    provider
                                }
                            }
                            current.copy(
                                providers = updated,
                                terminalProviderPromptHandled = true,
                            )
                        }
                    }
                }) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    prompted = false
                    markHandled()
                }) {
                    Text("以后再说")
                }
            },
        )
    }
}
