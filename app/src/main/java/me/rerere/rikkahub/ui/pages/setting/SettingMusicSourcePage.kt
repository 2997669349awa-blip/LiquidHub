// Modified by AI Hello World on 2026-10-04.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 设置 > 其他 > 音乐源：添加音乐源（写名称）→ 导入 manifest.json / index.js → 测试可用 → 启用。

package me.rerere.rikkahub.ui.pages.setting

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.music.MusicSourceInfo
import me.rerere.rikkahub.data.music.MusicSourceRegistry
import me.rerere.rikkahub.data.music.MusicSourceStore
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
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var sources by remember { mutableStateOf(MusicSourceStore.list(context)) }
    var addDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var activeName by remember { mutableStateOf(MusicSourceRegistry.activeName(context)) }
    var testing by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val name = pendingImport
        pendingImport = null
        if (name == null || uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                uris.all { uri ->
                    val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) c.getString(idx) else null
                        } else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@all false
                    MusicSourceStore.writeFile(context, name, fileName, bytes)
                }
            }
            sources = MusicSourceStore.list(context)
            toaster.show(
                message = if (ok) "文件已导入" else "导入失败",
                type = if (ok) ToastType.Success else ToastType.Error,
            )
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.music_source_title)) },
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
                text = stringResource(R.string.music_source_page_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(onClick = { addDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(HugeIcons.Add01, contentDescription = null)
                Text(stringResource(R.string.music_source_add), modifier = Modifier.padding(start = 8.dp))
            }

            if (sources.isEmpty()) {
                Text(
                    text = stringResource(R.string.music_source_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            sources.forEach { info ->
                MusicSourceCard(
                    info = info,
                    active = info.name == activeName,
                    expanded = selected == info.name,
                    testing = testing && selected == info.name,
                    onToggle = { selected = if (selected == info.name) null else info.name },
                    onImport = {
                        pendingImport = info.name
                        picker.launch(arrayOf("*/*"))
                    },
                    onTest = {
                        selected = info.name
                        testing = true
                        scope.launch {
                            val source = withContext(Dispatchers.IO) { MusicSourceRegistry.load(context, info.name) }
                            val ok = withContext(Dispatchers.IO) { (source as? me.rerere.rikkahub.data.music.JsMusicSource)?.validate() == true }
                            testing = false
                            if (ok) {
                                MusicSourceRegistry.setActive(context, info.name)
                                activeName = info.name
                                toaster.show(message = "测试通过，已启用", type = ToastType.Success)
                            } else {
                                toaster.show(message = "测试未通过：请确认 manifest.json 的 type=music 且 index.js 定义了 search/songUrl", type = ToastType.Error)
                            }
                        }
                    },
                    onEnable = {
                        if (MusicSourceRegistry.setActive(context, info.name)) {
                            activeName = info.name
                            toaster.show(message = "已启用", type = ToastType.Success)
                        } else {
                            toaster.show(message = "启用失败，请先通过测试", type = ToastType.Error)
                        }
                    },
                    onDelete = {
                        MusicSourceStore.delete(context, info.name)
                        if (activeName == info.name) {
                            MusicSourceRegistry.setActive(context, null)
                            activeName = null
                        }
                        sources = MusicSourceStore.list(context)
                        if (selected == info.name) selected = null
                    },
                )
            }

            if (activeName != null) {
                Button(onClick = { navController.navigate(Screen.Music) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.music_source_open))
                }
            }
        }
    }

    if (addDialog) {
        AddSourceDialog(
            onDismiss = { addDialog = false },
            onConfirm = { name ->
                val dir = MusicSourceStore.create(context, name)
                addDialog = false
                if (dir != null) {
                    sources = MusicSourceStore.list(context)
                    selected = dir.name
                    toaster.show(message = "已创建，请导入 manifest.json 与 index.js", type = ToastType.Normal)
                } else {
                    toaster.show(message = "名称无效", type = ToastType.Error)
                }
            },
        )
    }
}

@Composable
private fun MusicSourceCard(
    info: MusicSourceInfo,
    active: Boolean,
    expanded: Boolean,
    testing: Boolean,
    onToggle: () -> Unit,
    onImport: () -> Unit,
    onTest: () -> Unit,
    onEnable: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var files by remember(info.name, expanded) { mutableStateOf(if (expanded) MusicSourceStore.files(context, info.name) else emptyList()) }

    CardGroup(title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(info.name)
            if (active) {
                Icon(
                    HugeIcons.CheckmarkCircle02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }) {
        item(
            onClick = onToggle,
            headlineContent = {
                Text(
                    if (expanded) "收起" else "展开/管理",
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            supportingContent = {
                Text(
                    if (info.valid) "有效（已含 music 与入口文件）" else "无效：需要 manifest.json(type=music) + index.js",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (info.valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            },
        )
        if (testing) {
            item(headlineContent = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) })
        }
        if (expanded) {
            item(
                headlineContent = {
                    Text(
                        if (files.isEmpty()) "（还没有文件）" else files.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                supportingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onImport) { Text("添加文件") }
                        Button(onClick = onTest) { Text("测试") }
                        if (!active) Button(onClick = onEnable) { Text("启用") }
                    }
                },
                trailingContent = {
                    IconButton(onClick = onDelete) {
                        Icon(HugeIcons.Delete01, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                    }
                },
            )
        }
    }
}

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.music_source_add)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.music_source_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
