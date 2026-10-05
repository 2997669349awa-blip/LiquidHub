// Modified by AI Hello World on 2026-10-05.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 扮演（Cosplay）：用户自定义角色，每个角色一个独立助手（设定词即系统提示词），
// 每个角色扮演都是独立对话，上下文窗口可自由调节。

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun SettingCosplayPage() {
    val settingsStore = koinInject<SettingsStore>()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val characters = settings.assistants.filter { it.isCosplay }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun createCharacter() {
        val created = Assistant(
            isCosplay = true,
            name = "新角色",
            systemPrompt = "你正在扮演一个角色。请始终以该角色的身份、语气和设定来回应，不要跳出角色。",
            useGradientBackground = true,
        )
        scope.launch { settingsStore.update { it.copy(assistants = it.assistants + created) } }
        navController.navigate(Screen.AssistantDetail(created.id.toString()))
    }

    fun startRoleplay(assistant: Assistant) {
        scope.launch { settingsStore.update { it.copy(assistantId = assistant.id) } }
        navController.navigate(Screen.Chat(Uuid.random().toString()))
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("扮演") },
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
                text = "创建一个角色，AI 会直接把角色设定注入系统提示词，以该角色身份对话。每个角色都是独立对话，可在角色详情里自由调节上下文窗口与模型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CardGroup {
                item(
                    headlineContent = { Text("＋ 新建角色") },
                    supportingContent = { Text("自定义名字、头像、设定词、开场白与上下文窗口") },
                    leadingContent = { Icon(HugeIcons.MagicWand01, contentDescription = null) },
                    onClick = { createCharacter() },
                )
            }

            if (characters.isNotEmpty()) {
                CardGroup {
                    characters.forEach { character ->
                        item(
                            headlineContent = {
                                Text(character.name.ifBlank { "未命名角色" })
                            },
                            supportingContent = {
                                Text(
                                    character.systemPrompt
                                        .replace("\n", " ")
                                        .take(48)
                                        .ifBlank { "（未设置设定词）" }
                                )
                            },
                            leadingContent = {
                                Icon(HugeIcons.MagicWand01, contentDescription = null)
                            },
                            trailingContent = {
                                TextButton(onClick = { startRoleplay(character) }) {
                                    Text("开始对话")
                                }
                            },
                            onClick = {
                                navController.navigate(Screen.AssistantDetail(character.id.toString()))
                            },
                        )
                    }
                }
            } else {
                Text(
                    text = "还没有角色，点上面的「新建角色」开始。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
