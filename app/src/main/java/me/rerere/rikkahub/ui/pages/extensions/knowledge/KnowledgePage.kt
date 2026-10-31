package me.rerere.rikkahub.ui.pages.extensions.knowledge

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiSearch02
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.DatabaseRestore
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.rikkahub.data.db.rag.RAG_SOURCE_DOCUMENT
import me.rerere.rikkahub.data.db.rag.RAG_SOURCE_WORKSPACE
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun KnowledgePage() {
    val vm: KnowledgeVM = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("知识库") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("索引概览") },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Database02, null) },
                        headlineContent = { Text("${state.stats.totalChunks} 个段落") },
                        supportingContent = {
                            Text(
                                "来源 ${state.stats.sourceCount} 个 · 文档 ${state.stats.byType[RAG_SOURCE_DOCUMENT] ?: 0} 段 · 工作区 ${state.stats.byType[RAG_SOURCE_WORKSPACE] ?: 0} 段"
                            )
                        },
                    )
                    if (state.stats.lastIndexedAt > 0) {
                        item(
                            headlineContent = { Text("最近索引时间") },
                            supportingContent = {
                                Text(
                                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                        .format(Date(state.stats.lastIndexedAt))
                                )
                            },
                        )
                    }
                    if (state.indexing) {
                        item(
                            headlineContent = { Text(state.progressLabel.ifBlank { "正在索引" }) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (state.progressTotal > 0) {
                                        LinearProgressIndicator(
                                            progress = {
                                                (state.progressIndex.toFloat() / state.progressTotal).coerceIn(0f, 1f)
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Text(
                                            "${state.progressIndex}/${state.progressTotal}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("索引来源") },
                ) {
                    item(
                        onClick = { vm.indexUploads() },
                        leadingContent = { Icon(HugeIcons.DatabaseRestore, null) },
                        headlineContent = { Text("索引上传文档") },
                        supportingContent = { Text("PDF / Word / PPT / EPUB / 文本，增量索引") },
                    )
                    item(
                        onClick = { showClearConfirm = true },
                        leadingContent = { Icon(HugeIcons.Delete02, null) },
                        headlineContent = { Text("清空索引") },
                        supportingContent = { Text("只删除索引，不删除原始文件") },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("工作区文件") },
                ) {
                    if (workspaces.isEmpty()) {
                        item(
                            headlineContent = { Text("暂无工作区") },
                            supportingContent = { Text("在扩展中创建工作区后可索引其中的文本文件") },
                        )
                    } else {
                        workspaces.forEach { workspace ->
                            item(
                                headlineContent = { Text(workspace.name) },
                                supportingContent = {
                                    Text(
                                        workspace.shellStatus.lowercase(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingContent = {
                                    TextButton(
                                        onClick = { vm.indexWorkspace(workspace.id) },
                                        enabled = !state.indexing,
                                    ) { Text("索引") }
                                },
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("检索预览") },
                ) {
                    item(
                        headlineContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = state.searchQuery,
                                    onValueChange = vm::updateSearchQuery,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("输入关键词") },
                                    singleLine = true,
                                    trailingIcon = {
                                        if (state.searching) {
                                            CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                                        }
                                    },
                                )
                                Button(
                                    onClick = { vm.search() },
                                    enabled = state.searchQuery.isNotBlank() && !state.searching,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(HugeIcons.AiSearch02, null)
                                    Text("搜索", modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        },
                    )
                    state.results.forEach { hit ->
                        item(
                            headlineContent = { Text(hit.title.ifBlank { hit.sourceKey }) },
                            supportingContent = {
                                Text(
                                    hit.snippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                    if (state.results.isEmpty() && state.searchQuery.isNotBlank() && !state.searching) {
                        item(
                            headlineContent = {
                                Text(
                                    "没有匹配结果",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空知识库索引？") },
            text = { Text("原始文件不会被删除，之后可以重新索引。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearIndex()
                    showClearConfirm = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}
