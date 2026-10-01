package me.rerere.liquidhub.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LiquidColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF00286B),
    background = Color(0xFF0B1220),
    surface = Color(0xCC131C2E),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0x33FFFFFF),
    onSurfaceVariant = Color(0xFFB9C2D0),
    error = Color(0xFFFFB4AB),
)

private val MODEL_PRESETS = listOf("gpt-4o-mini", "gpt-4o", "deepseek-chat", "qwen-plus", "glm-4-flash")

@Composable
fun LiquidHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LiquidColors, content = content)
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x22FFFFFF))
            .padding(12.dp),
        content = content,
    )
}

@Composable
fun App() {
    val state = remember { AppState().also { it.load() } }
    val scope = rememberCoroutineScope()
    var showSidebar by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }

    LiquidHubTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF0F172A), Color(0xFF1D4ED8)))),
        ) {
            if (showSettings) {
                SettingsScreen(state, onBack = { showSettings = false })
            } else {
                Row(Modifier.fillMaxSize()) {
                    ChatArea(state, scope, Modifier.weight(1f).fillMaxHeight())
                    RightRail(
                        expanded = showSidebar,
                        onToggleSidebar = { showSidebar = !showSidebar },
                        onNewChat = { state.newConversation() },
                    )
                    AnimatedVisibility(visible = showSidebar) {
                        Sidebar(
                            state = state,
                            onSelect = { state.select(it) },
                            onOpenSettings = { showSettings = true },
                            onNewChat = { state.newConversation() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatArea(state: AppState, scope: CoroutineScope, modifier: Modifier) {
    val conversation = state.current
    Column(modifier.padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = conversation?.title ?: "LiquidHub",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ModelPicker(state)
            FilterChip(
                selected = state.settings.webSearch,
                onClick = {
                    state.updateSettings(state.settings.copy(webSearch = !state.settings.webSearch))
                },
                label = { Text("联网搜索") },
            )
        }
        Spacer(Modifier.height(8.dp))
        GlassPanel(Modifier.weight(1f).fillMaxWidth()) {
            val messages = conversation?.messages.orEmpty()
            val listState = rememberLazyListState()
            LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages) { m -> MessageBubble(m) }
            }
            state.toolNotice?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        var input by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (state.sending) "正在生成…" else "输入消息，点「发送」") },
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = !state.sending && input.isNotBlank(),
                onClick = {
                    state.send(scope, input)
                    input = ""
                },
            ) { Text("发送") }
        }
    }
}

@Composable
private fun ModelPicker(state: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(state.settings.model) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MODEL_PRESETS.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m) },
                    onClick = {
                        state.updateSettings(state.settings.copy(model = m))
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage) {
    when (m.role) {
        "user" -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 620.dp),
            ) {
                Text(
                    text = m.content.orEmpty(),
                    modifier = Modifier.padding(10.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        "assistant" -> {
            val content = m.content
            if (!content.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Surface(
                        color = Color(0x22FFFFFF),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.widthIn(max = 720.dp),
                    ) {
                        MarkdownText(content, Modifier.padding(10.dp))
                    }
                }
            }
        }

        "tool" -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            Surface(color = Color(0x14FFFFFF), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        text = "工具 · ${m.name ?: ""}",
                        fontSize = 11.sp,
                        color = Color(0xFF8AB4FF),
                    )
                    Text(
                        text = m.content.orEmpty().take(600),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RightRail(expanded: Boolean, onToggleSidebar: () -> Unit, onNewChat: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(56.dp)
            .background(Color(0x22000000))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onToggleSidebar) { Text(if (expanded) "×" else "☰", fontSize = 18.sp) }
        TextButton(onClick = onNewChat) { Text("＋", fontSize = 18.sp) }
    }
}

@Composable
private fun Sidebar(
    state: AppState,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    val workspaceTree = remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(tab, state.settings.workspaceDir) {
        workspaceTree.value = if (tab == 1 && state.settings.workspaceDir.isNotBlank()) {
            withContext(Dispatchers.IO) {
                runCatching { Workspace(state.settings.workspaceDir, false).tree() }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(Color(0x33FFFFFF))
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("对话") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("工作区") })
            Spacer(Modifier.weight(1f))
            if (tab == 0) TextButton(onClick = onNewChat) { Text("新建") }
        }
        Spacer(Modifier.height(8.dp))
        if (tab == 0) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.conversations, key = { it.id }) { c ->
                    val selected = c.id == state.currentId
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color(0x33FFFFFF) else Color(0x14FFFFFF))
                            .clickable { onSelect(c.id) }
                            .padding(10.dp),
                    ) {
                        Text(c.title, maxLines = 1)
                    }
                }
            }
        } else {
            val dir = state.settings.workspaceDir
            if (dir.isBlank()) {
                Text("未设置工作区目录。到「设置」里填写一个本地文件夹路径。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(dir, fontSize = 11.sp, color = Color(0xFF8AB4FF), maxLines = 2)
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(workspaceTree.value) { line ->
                        Text(line, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("设置", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SettingsScreen(state: AppState, onBack: () -> Unit) {
    var baseUrl by remember { mutableStateOf(state.settings.baseUrl) }
    var apiKey by remember { mutableStateOf(state.settings.apiKey) }
    var model by remember { mutableStateOf(state.settings.model) }
    var searchProvider by remember { mutableStateOf(state.settings.searchProvider) }
    var searchApiKey by remember { mutableStateOf(state.settings.searchApiKey) }
    var workspaceDir by remember { mutableStateOf(state.settings.workspaceDir) }
    var allowCommands by remember { mutableStateOf(state.settings.allowCommands) }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("设置", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回") }
        }

        GlassPanel(Modifier.fillMaxWidth()) {
            Text("模型服务（OpenAI 兼容）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(model, { model = it }, label = { Text("默认模型") }, modifier = Modifier.fillMaxWidth())
        }

        GlassPanel(Modifier.fillMaxWidth()) {
            Text("联网搜索", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("tavily" to "Tavily", "exa" to "Exa").forEach { (id, label) ->
                    FilterChip(
                        selected = searchProvider == id,
                        onClick = { searchProvider = id },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                searchApiKey,
                { searchApiKey = it },
                label = { Text("搜索 API Key") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        GlassPanel(Modifier.fillMaxWidth()) {
            Text("本地工作区", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                workspaceDir,
                { workspaceDir = it },
                label = { Text("工作区根目录（绝对路径）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("允许 AI 执行 shell 命令", modifier = Modifier.weight(1f))
                Switch(checked = allowCommands, onCheckedChange = { allowCommands = it })
            }
        }

        Button(onClick = {
            state.updateSettings(
                state.settings.copy(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    searchProvider = searchProvider,
                    searchApiKey = searchApiKey,
                    workspaceDir = workspaceDir,
                    allowCommands = allowCommands,
                )
            )
            onBack()
        }) { Text("保存") }

        Text(
            "Windows 版开发中：本地模型、云同步等会在后续版本加入。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}
