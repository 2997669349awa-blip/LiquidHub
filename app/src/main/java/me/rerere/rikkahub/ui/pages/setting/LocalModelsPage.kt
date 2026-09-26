// Modified by AI Hello World on 2026-09-26.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// "本地AI" 模型管理页：从国内可达的镜像下载开源 GGUF 模型到本机。

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val HF_MIRROR = "https://hf-mirror.com"
private const val MODELS_DIR = "localmodels"

private data class LocalModelItem(
    val id: String,
    val name: String,
    val desc: String,
    val size: String,
    val fileName: String,
    val url: String,
)

// 国内可直连的 hf-mirror 镜像；文件名为单文件 Q4_K_M。若某条失效，可用页面里的「自定义链接」。
private val LOCAL_MODEL_CATALOG = listOf(
    LocalModelItem(
        id = "qwen2.5-0.5b",
        name = "Qwen2.5 0.5B Instruct",
        desc = "通义千问 2.5 最小对话模型，体积小、速度快",
        size = "约 0.4 GB",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        url = "$HF_MIRROR/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
    ),
    LocalModelItem(
        id = "qwen2.5-1.5b",
        name = "Qwen2.5 1.5B Instruct",
        desc = "通义千问 2.5 1.5B，日常对话够用",
        size = "约 1.0 GB",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        url = "$HF_MIRROR/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
    ),
    LocalModelItem(
        id = "deepseek-r1-qwen-1.5b",
        name = "DeepSeek-R1-Distill-Qwen 1.5B",
        desc = "DeepSeek R1 蒸馏版，擅长推理",
        size = "约 1.0 GB",
        fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
        url = "$HF_MIRROR/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
    ),
    LocalModelItem(
        id = "llama-3.2-1b",
        name = "Llama 3.2 1B Instruct",
        desc = "Meta Llama 3.2 1B，轻量通用",
        size = "约 0.8 GB",
        fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        url = "$HF_MIRROR/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
    ),
)

@Composable
fun LocalModelsPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val dir = remember { File(context.filesDir, MODELS_DIR).apply { mkdirs() } }
    val downloading = remember { mutableStateMapOf<String, Float>() }
    val downloaded = remember { mutableStateMapOf<String, Boolean>() }
    var error by remember { mutableStateOf<String?>(null) }

    var customUrl by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }

    fun refresh() {
        dir.listFiles()?.forEach { if (it.name.endsWith(".gguf")) downloaded[it.name] = true }
        // 清掉已不存在（被删除）的项
        val names = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        downloaded.keys.toList().forEach { if (it !in names) downloaded.remove(it) }
    }
    remember { refresh() }

    fun download(url: String, fileName: String) {
        val name = fileName.trim()
        if (url.isBlank() || name.isBlank()) {
            error = "链接和文件名都不能为空"
            return
        }
        if (downloading.containsKey(name)) return
        error = null
        scope.launch {
            downloading[name] = 0f
            try {
                val tmp = File(dir, "$name.part")
                val out = File(dir, name)
                withContext(Dispatchers.IO) {
                    val conn = (URL(url.trim()).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        setRequestProperty("User-Agent", "LiquidHub/0.1")
                    }
                    try {
                        val code = conn.responseCode
                        if (code !in 200..299) throw RuntimeException("HTTP $code")
                        val total = conn.contentLengthLong
                        conn.inputStream.use { input ->
                            tmp.outputStream().use { output ->
                                val buf = ByteArray(256 * 1024)
                                var read = 0L
                                var last = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    read += n
                                    if (total > 0 && read - last > total / 200) {
                                        last = read
                                        downloading[name] = (read.toDouble() / total).toFloat().coerceIn(0f, 1f)
                                    }
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                if (out.exists()) out.delete()
                tmp.renameTo(out)
                downloading.remove(name)
                refresh()
            } catch (t: Throwable) {
                downloading.remove(name)
                error = "下载失败（$name）：${t.message}"
            }
        }
    }

    fun delete(fileName: String) {
        runCatching { File(dir, fileName).delete() }
        downloaded.remove(fileName)
        refresh()
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("本地AI") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("自定义链接下载", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "支持 hf-mirror / ModelScope 等国内直连的 GGUF 直链。下载后可在终端 Ollama 里用该 gguf 创建模型。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("模型直链 (https://...)") },
                        )
                        OutlinedTextField(
                            value = customName,
                            onValueChange = { customName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("保存文件名 (xxx.gguf)") },
                        )
                        Button(
                            enabled = customUrl.isNotBlank() && customName.isNotBlank(),
                            onClick = { download(customUrl, customName) },
                        ) { Text("下载") }
                    }
                }
            }

            error?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                Text("精选开源模型（国内镜像）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            }

            items(LOCAL_MODEL_CATALOG, key = { it.id }) { model ->
                val isDownloading = downloading.containsKey(model.fileName)
                val isDownloaded = downloaded[model.fileName] == true || File(dir, model.fileName).exists()
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(model.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            model.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${model.size} · ${model.fileName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isDownloading) {
                            val p = downloading[model.fileName] ?: 0f
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            Text("下载中 ${(p * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isDownloaded) {
                                    Text("已下载", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                    OutlinedButton(onClick = { delete(model.fileName) }) {
                                        Icon(HugeIcons.Delete01, contentDescription = null)
                                        Text("删除")
                                    }
                                } else {
                                    Button(onClick = { download(model.url, model.fileName) }) {
                                        Icon(HugeIcons.Download04, contentDescription = null)
                                        Text("下载")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
