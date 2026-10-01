package me.rerere.liquidhub.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderSetting

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
                SettingsScreen(state, scope, onBack = { showSettings = false })
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
            Column(Modifier.weight(1f)) {
                Text(text = conversation?.title ?: "LiquidHub", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("助手：${state.currentAssistant?.name ?: "-"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ModelPicker(state)
            FilterChip(
                selected = state.settings.webSearch,
                onClick = { state.updateSettings(state.settings.copy(webSearch = !state.settings.webSearch)) },
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
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
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
    val provider = state.providers.firstOrNull { it.id == state.currentAssistant?.providerId } ?: state.selectedProvider
    val model = provider?.models?.firstOrNull { it.id == state.currentAssistant?.modelId } ?: state.selectedModel
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("${provider?.name ?: "未选择服务"} / ${model?.displayName?.ifBlank { model.modelId } ?: "无模型"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.providers.forEach { p ->
                DropdownMenuItem(
                    text = { Text("服务：${p.name}", fontWeight = FontWeight.Bold) },
                    onClick = {
                        state.selectProvider(p.id)
                        p.models.firstOrNull()?.let { state.setAssistantModel(p.id, it.id) }
                        open = false
                    },
                )
                p.models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text("    ${m.displayName.ifBlank { m.modelId }}") },
                        onClick = {
                            state.selectProvider(p.id)
                            state.selectModel(m.id)
                            state.setAssistantModel(p.id, m.id)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage) {
    when (m.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 620.dp),
            ) {
                Text(m.content.orEmpty(), modifier = Modifier.padding(10.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        "assistant" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                color = Color(0x22FFFFFF),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 760.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    m.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning -> ReasoningBlock(reasoning) }
                    MarkdownText(m.content.orEmpty())
                    val clipboard = LocalClipboardManager.current
                    TextButton(onClick = { clipboard.setText(AnnotatedString(m.content.orEmpty())) }) {
                        Text("复制", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(reasoning: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            text = (if (expanded) "▾ " else "▸ ") + "思考过程",
            fontSize = 11.sp,
            color = Color(0xFF9DB4D0),
            modifier = Modifier.clickable { expanded = !expanded },
        )
        if (expanded) {
            Text(reasoning, fontSize = 11.sp, color = Color(0xFF9DB4D0), fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun RightRail(expanded: Boolean, onToggleSidebar: () -> Unit, onNewChat: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxHeight().width(56.dp).background(Color(0x22000000)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onToggleSidebar) { Text(if (expanded) "×" else "☰", fontSize = 18.sp) }
        TextButton(onClick = onNewChat) { Text("＋", fontSize = 18.sp) }
    }
}

@Composable
private fun AssistantPicker(state: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("助手：${state.currentAssistant?.name ?: "无"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.assistants.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.name) },
                    onClick = {
                        state.selectAssistant(a.id)
                        open = false
                    },
                )
            }
        }
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
    var renameId by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
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
        modifier = Modifier.fillMaxHeight().width(320.dp).background(Color(0x33FFFFFF)).padding(12.dp),
    ) {
        AssistantPicker(state)
        Spacer(Modifier.height(8.dp))
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color(0x33FFFFFF) else Color(0x14FFFFFF)),
                    ) {
                        Text(
                            text = c.title,
                            maxLines = 1,
                            modifier = Modifier.weight(1f).clickable { onSelect(c.id) }.padding(10.dp),
                        )
                        Box {
                            TextButton(onClick = { menuFor = c.id }) { Text("⋮", fontSize = 14.sp) }
                            DropdownMenu(expanded = menuFor == c.id, onDismissRequest = { menuFor = null }) {
                                DropdownMenuItem(text = { Text("重命名") }, onClick = { renameId = c.id; menuFor = null })
                                DropdownMenuItem(text = { Text("删除") }, onClick = { state.deleteConversation(c.id); menuFor = null })
                            }
                        }
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
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("设置", fontWeight = FontWeight.Bold)
        }
    }

    renameId?.let { id ->
        val conv = state.conversations.firstOrNull { it.id == id }
        var text by remember(id) { mutableStateOf(conv?.title ?: "") }
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text("重命名对话") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    state.renameConversation(id, text.ifBlank { "未命名" })
                    renameId = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SettingsScreen(state: AppState, scope: CoroutineScope, onBack: () -> Unit) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("设置", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回") }
        }

        AssistantSection(state)
        ProviderSection(state, scope)
        SearchSection(state)
        WorkspaceSection(state)
    }
}

@Composable
private fun AssistantSection(state: AppState) {
    val assistant = state.currentAssistant
    GlassPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("助手", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.addAssistant() }) { Text("＋ 添加助手") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.assistants.forEach { a ->
                FilterChip(selected = a.id == state.selectedAssistantId, onClick = { state.selectAssistant(a.id) }, label = { Text(a.name) })
            }
        }
        if (assistant == null) return@GlassPanel
        Spacer(Modifier.height(8.dp))
        var name by remember(assistant.id) { mutableStateOf(assistant.name) }
        var prompt by remember(assistant.id) { mutableStateOf(assistant.systemPrompt) }
        var temp by remember(assistant.id) { mutableStateOf(assistant.temperature?.toString() ?: "") }
        OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(prompt, { prompt = it }, label = { Text("系统提示词") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(temp, { temp = it }, label = { Text("温度（可选，0~2）") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("模型绑定：${providerName(state, assistant.providerId)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            state.updateAssistant(
                assistant.copy(
                    name = name.ifBlank { "助手" },
                    systemPrompt = prompt,
                    temperature = temp.toFloatOrNull(),
                )
            )
        }) { Text("保存助手") }
    }
}

private fun providerName(state: AppState, id: kotlin.uuid.Uuid?): String {
    val p = state.providers.firstOrNull { it.id == id } ?: return "未绑定（跟随全局）"
    val m = p.models.firstOrNull { it.id == state.currentAssistant?.modelId }
    return "${p.name} / ${m?.displayName?.ifBlank { m.modelId } ?: "默认模型"}"
}

@Composable
private fun ProviderSection(state: AppState, scope: CoroutineScope) {
    val provider = state.selectedProvider
    GlassPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模型服务", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                state.addProvider(ProviderSetting.OpenAI(name = "新服务", baseUrl = "https://api.openai.com/v1"))
            }) { Text("＋ 添加服务") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.providers.forEach { p ->
                FilterChip(selected = p.id == state.selectedProviderId, onClick = { state.selectProvider(p.id) }, label = { Text(p.name) })
            }
        }
        if (provider == null) {
            Text("还没有服务，点右上角「添加服务」。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@GlassPanel
        }
        Spacer(Modifier.height(8.dp))
        var name by remember(provider.id) { mutableStateOf(provider.name) }
        var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl()) }
        var apiKey by remember(provider.id) { mutableStateOf(provider.apiKey()) }
        OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                state.updateProvider(provider.withNameUrlKey(name, baseUrl, apiKey))
            }) { Text("保存") }
            OutlinedButton(onClick = { state.fetchModels(scope, provider) }) { Text("拉取模型") }
        }
        Spacer(Modifier.height(8.dp))
        Text("模型（${provider.models.size}）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            provider.models.forEach { m ->
                FilterChip(selected = m.id == state.selectedModelId, onClick = { state.selectModel(m.id) }, label = { Text(m.displayName.ifBlank { m.modelId }) })
            }
        }
    }
}

@Composable
private fun SearchSection(state: AppState) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Text("联网搜索", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        var provider by remember { mutableStateOf(state.settings.searchProvider) }
        var key by remember { mutableStateOf(state.settings.searchApiKey) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("tavily" to "Tavily", "exa" to "Exa").forEach { (id, label) ->
                FilterChip(selected = provider == id, onClick = { provider = id }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(key, { key = it }, label = { Text("搜索 API Key") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = { state.updateSettings(state.settings.copy(searchProvider = provider, searchApiKey = key)) }) { Text("保存搜索设置") }
    }
}

@Composable
private fun WorkspaceSection(state: AppState) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Text("本地工作区", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        var dir by remember { mutableStateOf(state.settings.workspaceDir) }
        var allow by remember { mutableStateOf(state.settings.allowCommands) }
        OutlinedTextField(dir, { dir = it }, label = { Text("工作区根目录（绝对路径）") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("允许 AI 执行 shell 命令", modifier = Modifier.weight(1f))
            Switch(checked = allow, onCheckedChange = { allow = it })
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { state.updateSettings(state.settings.copy(workspaceDir = dir, allowCommands = allow)) }) { Text("保存工作区设置") }
    }
}

private fun ProviderSetting.baseUrl(): String = when (this) {
    is ProviderSetting.OpenAI -> baseUrl
    is ProviderSetting.Google -> baseUrl
    is ProviderSetting.Claude -> baseUrl
}

private fun ProviderSetting.apiKey(): String = when (this) {
    is ProviderSetting.OpenAI -> apiKey
    is ProviderSetting.Google -> apiKey
    is ProviderSetting.Claude -> apiKey
}

private fun ProviderSetting.withNameUrlKey(name: String, url: String, key: String): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(name = name, baseUrl = url, apiKey = key)
    is ProviderSetting.Google -> copy(name = name, baseUrl = url, apiKey = key)
    is ProviderSetting.Claude -> copy(name = name, baseUrl = url, apiKey = key)
}
