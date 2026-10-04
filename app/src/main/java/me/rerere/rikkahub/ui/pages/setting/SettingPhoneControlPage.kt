// Modified by AI Hello World on 2026-10-04.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 设置 > 其他 > 手机控制：授权 Shizuku / 无障碍 / 悬浮窗，并为当前助手启用手机控制。

package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.phone.PhoneControlManager
import me.rerere.rikkahub.data.phone.ShizukuShell
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import rikka.shizuku.Shizuku

@Composable
fun SettingPhoneControlPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val assistant = settings.getCurrentAssistant()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 授权页返回后刷新状态
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) refresh++
        }
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
        onDispose { runCatching { Shizuku.removeRequestPermissionResultListener(listener) } }
    }

    val shizukuAlive = remember(refresh) { PhoneControlManager.isShizukuBinderAlive }
    val shizukuGranted = remember(refresh) { PhoneControlManager.hasShizukuPermission }
    val accessibilityEnabled = remember(refresh) { PhoneControlManager.isAccessibilityEnabled }
    val overlayGranted = remember(refresh) { PhoneControlManager.hasOverlayPermission() }
    val phoneControlEnabled = assistant.localTools.contains(LocalToolOption.PhoneControl)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.phone_control_page_title)) },
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
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.phone_control_page_shizuku)) },
                    supportingContent = {
                        Text(
                            when {
                                !shizukuAlive -> stringResource(R.string.phone_control_page_shizuku_not_running)
                                shizukuGranted -> stringResource(R.string.phone_control_page_granted)
                                else -> stringResource(R.string.phone_control_page_not_granted)
                            }
                        )
                    },
                    trailingContent = {
                        Button(
                            enabled = shizukuAlive && !shizukuGranted,
                            onClick = { ShizukuShell.requestPermission() },
                        ) { Text(stringResource(R.string.permission_go_grant)) }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.phone_control_page_accessibility)) },
                    supportingContent = {
                        Text(
                            if (accessibilityEnabled) stringResource(R.string.phone_control_page_granted)
                            else stringResource(R.string.phone_control_page_not_granted)
                        )
                    },
                    trailingContent = {
                        Button(
                            enabled = !accessibilityEnabled,
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            },
                        ) { Text(stringResource(R.string.permission_go_grant)) }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.phone_control_page_overlay)) },
                    supportingContent = {
                        Text(
                            if (overlayGranted) stringResource(R.string.phone_control_page_granted)
                            else stringResource(R.string.phone_control_page_not_granted)
                        )
                    },
                    trailingContent = {
                        Button(
                            enabled = !overlayGranted,
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        )
                                    )
                                }
                            },
                        ) { Text(stringResource(R.string.permission_go_grant)) }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.phone_control_page_enable)) },
                    supportingContent = { Text(stringResource(R.string.phone_control_page_enable_desc)) },
                    trailingContent = {
                        Switch(
                            checked = phoneControlEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    settingsStore.update { s ->
                                        s.copy(
                                            assistants = s.assistants.map { a ->
                                                if (a.id == assistant.id) {
                                                    a.copy(
                                                        localTools = if (enabled) {
                                                            (a.localTools + LocalToolOption.PhoneControl).distinct()
                                                        } else {
                                                            a.localTools - LocalToolOption.PhoneControl
                                                        },
                                                    )
                                                } else {
                                                    a
                                                }
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    },
                )
            }

            Text(
                text = stringResource(R.string.phone_control_page_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
