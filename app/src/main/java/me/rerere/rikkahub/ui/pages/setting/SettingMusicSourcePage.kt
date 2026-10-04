// Modified by AI Hello World on 2026-10-04.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 设置 > 其他 > 音乐源：生成加密音乐源、输入 32 位密码解锁内置网易云音乐。

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.music.MusicSourceManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
fun SettingMusicSourcePage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var unlocked by remember { mutableStateOf(MusicSourceManager.isUnlocked(context)) }
    var trigger by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var archivePath by remember { mutableStateOf<String?>(null) }

    val title = stringResource(R.string.music_source_title)
    val desc = stringResource(R.string.music_source_desc)
    val step1 = stringResource(R.string.music_source_step1)
    val triggerHint = stringResource(R.string.music_source_trigger_hint)
    val triggerWrong = stringResource(R.string.music_source_trigger_wrong)
    val generate = stringResource(R.string.music_source_generate)
    val generated = stringResource(R.string.music_source_generated)
    val generateFailed = stringResource(R.string.music_source_generate_failed)
    val step2 = stringResource(R.string.music_source_step2)
    val passwordHint = stringResource(R.string.music_source_password_hint)
    val unlock = stringResource(R.string.music_source_unlock)
    val unlockedMsg = stringResource(R.string.music_source_unlocked)
    val unlockFailed = stringResource(R.string.music_source_unlock_failed)
    val statusEnabled = stringResource(R.string.music_source_status_enabled)
    val statusEnabledDesc = stringResource(R.string.music_source_status_enabled_desc)
    val openMusic = stringResource(R.string.music_source_open)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
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
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CardGroup(title = { Text(step1) }) {
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = trigger,
                            onValueChange = { trigger = it },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            singleLine = true,
                            label = { Text(triggerHint) },
                        )
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (trigger.trim() != MusicSourceManager.TRIGGER) {
                                        toaster.show(message = triggerWrong, type = ToastType.Error)
                                        return@Button
                                    }
                                    val file = MusicSourceManager.generateArchive(context)
                                    if (file != null) {
                                        archivePath = file.absolutePath
                                        toaster.show(message = generated, type = ToastType.Success)
                                    } else {
                                        toaster.show(message = generateFailed, type = ToastType.Error)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(generate) }
                            archivePath?.let {
                                Text(
                                    text = stringResource(R.string.music_source_generated_at, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
            }

            CardGroup(title = { Text(step2) }) {
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            singleLine = true,
                            label = { Text(passwordHint) },
                        )
                    },
                    supportingContent = {
                        Button(
                            onClick = {
                                val ok = MusicSourceManager.unlock(context, password)
                                if (ok) {
                                    unlocked = true
                                    toaster.show(message = unlockedMsg, type = ToastType.Success)
                                } else {
                                    toaster.show(message = unlockFailed, type = ToastType.Error)
                                }
                            },
                            enabled = password.length >= 8,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(unlock) }
                    },
                )
                if (unlocked) {
                    item(
                        headlineContent = { Text(statusEnabled) },
                        supportingContent = { Text(statusEnabledDesc) },
                        trailingContent = {
                            Button(onClick = { navController.navigate(Screen.Music) }) {
                                Text(openMusic)
                            }
                        },
                    )
                }
            }
        }
    }
}
