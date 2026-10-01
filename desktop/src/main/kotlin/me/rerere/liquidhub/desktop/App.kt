package me.rerere.liquidhub.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderSetting
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val Ink = Color(0xFF1F2329)
private val Subtle = Color(0xFF8A939E)
private val Line = Color(0xFFE5E6EB)
private val Panel = Color(0xFFF5F6F8)
private val Brand = Color(0xFF4D6BFE)
private val BrandSoft = Color(0xFFEDF1FF)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Panel,
    onSurfaceVariant = Subtle,
    error = Color(0xFFE5484D),
)

@Composable
fun LiquidHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}

/** 我们自己的标：一滴带高光的「液体」。 */
@Composable
fun LiquidHubLogo(size: Int = 24, tint: Color = Brand) {
    Canvas(modifier = Modifier.size(size.dp)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            cubicTo(w * 0.86f, h * 0.42f, w * 0.92f, h * 0.62f, w * 0.5f, h * 0.94f)
            cubicTo(w * 0.08f, h * 0.62f, w * 0.14f, h * 0.42f, w * 0.5f, h * 0.08f)
            close()
        }
        drawPath(path, brush = Brush.linearGradient(listOf(Brand, Color(0xFF7C5CFF))))
        drawCircle(color = Color.White.copy(alpha = 0.85f), radius = w * 0.09f, center = Offset(w * 0.38f, h * 0.52f))
    }
}

@Composable
fun App() {
    val state = remember { AppState().also { it.load() } }
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }

    LiquidHubTheme {
        Row(Modifier.fillMaxSize().background(Color.White)) {
            Sidebar(state, onOpenSettings = { showSettings = true }, onNewChat = { state.newConversation() })
            MainArea(state, scope, Modifier.weight(1f).fillMaxHeight())
        }
        if (showSettings) {
            SettingsDialog(state, scope, onClose = { showSettings = false })
        }
    }
}

@Composable
private fun Sidebar(state: AppState, onOpenSettings: () -> Unit, onNewChat: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val groups = remember(state.conversations.map { it.id to it.createdAt }) {
        groupConversations(state.conversations.toList())
    }

    Column(
        modifier = Modifier.fillMaxHeight().width(256.dp).background(Panel).padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiquidHubLogo(22)
            Spacer(Modifier.width(8.dp))
            Text("LiquidHub", fontWeight = FontWeight.Bold, color = Ink, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        SearchBox(query, { query = it })
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(BrandSoft)
                .clickable { onNewChat() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text("＋", color = Brand, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("开启新对话", color = Brand, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            groups.forEach { (label, items) ->
                item {
                    Text(label, fontSize = 12.sp, color = Subtle, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp))
                }
                items(items.filter { query.isBlank() || it.title.contains(query, true) }, key = { it.id }) { c ->
                    val selected = c.id == state.currentId
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color(0xFFE9EBF0) else Color.Transparent)
                            .clickable { state.select(c.id) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    ) {
                        Text(c.title, maxLines = 1, fontSize = 13.sp, color = Ink)
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onOpenSettings() }
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            LiquidHubLogo(20)
            Spacer(Modifier.width(8.dp))
            Text("设置", fontSize = 14.sp, color = Ink)
        }
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text("搜索", color = Subtle, fontSize = 13.sp)
                inner()
            },
        )
    }
}

@Composable
private fun MainArea(state: AppState, scope: CoroutineScope, modifier: Modifier) {
    val conversation = state.current
    val messages = conversation?.messages.orEmpty()
    Column(modifier) {
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiquidHubLogo(34)
                        Spacer(Modifier.width(10.dp))
                        Text("你好，我能帮什么忙吗？", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Ink)
                    }
                    Spacer(Modifier.height(24.dp))
                    Composer(state, scope, Modifier.widthIn(max = 720.dp).padding(horizontal = 24.dp))
                }
            }
        } else {
            val listState = rememberLazyListState()
            LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
                listState.animateScrollToItem(messages.size - 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(messages) { m -> MessageRow(m) }
                item { Spacer(Modifier.height(8.dp)) }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Composer(state, scope, Modifier.widthIn(max = 720.dp))
            }
        }
    }
}

@Composable
private fun Composer(state: AppState, scope: CoroutineScope, modifier: Modifier) {
    var input by remember { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(12.dp),
    ) {
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            textStyle = TextStyle(color = Ink, fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            decorationBox = { inner ->
                if (input.isEmpty()) Text("给 LiquidHub 发送消息", color = Subtle, fontSize = 15.sp)
                inner()
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelPill(state)
            Pill(
                text = "联网搜索",
                active = state.settings.webSearch,
                onClick = { state.updateSettings(state.settings.copy(webSearch = !state.settings.webSearch)) },
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (input.isNotBlank() && !state.sending) Brand else Color(0xFFD8DCE3))
                    .clickable(enabled = input.isNotBlank() && !state.sending) {
                        state.send(scope, input)
                        input = ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("↑", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        state.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Pill(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) BrandSoft else Color.White)
            .border(1.dp, if (active) Brand else Line, RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, fontSize = 13.sp, color = if (active) Brand else Subtle)
    }
}

@Composable
private fun ModelPill(state: AppState) {
    var open by remember { mutableStateOf(false) }
    val provider = state.providers.firstOrNull { it.id == state.currentAssistant?.providerId } ?: state.selectedProvider
    val model = provider?.models?.firstOrNull { it.id == state.currentAssistant?.modelId } ?: state.selectedModel
    Box {
        Pill(text = model?.displayName?.ifBlank { model.modelId } ?: "选择模型", active = false) { open = true }
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
private fun MessageRow(m: ChatMessage) {
    when (m.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(BrandSoft)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(m.content.orEmpty(), color = Ink, fontSize = 14.sp)
            }
        }

        "assistant" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Column(Modifier.widthIn(max = 760.dp)) {
                m.reasoning?.takeIf { it.isNotBlank() }?.let { ReasoningBlock(it) }
                MarkdownText(m.content.orEmpty())
            }
        }
    }
}

@Composable
private fun ReasoningBlock(reasoning: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .padding(10.dp),
    ) {
        Text(
            text = (if (expanded) "▾ " else "▸ ") + "已深度思考",
            fontSize = 12.sp,
            color = Subtle,
            modifier = Modifier.clickable { expanded = !expanded },
        )
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(reasoning, fontSize = 12.sp, color = Subtle)
        }
        Spacer(Modifier.height(4.dp))
    }
}

// ---------------- 设置 ----------------

@Composable
private fun SettingsDialog(state: AppState, scope: CoroutineScope, onClose: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    val pages = listOf("通用设置", "模型服务", "助手", "数据管理")
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.8f),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFF7F8FA),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("系统设置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClose) { Text("✕", color = Subtle) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.weight(1f)) {
                        Column(Modifier.width(160.dp)) {
                            pages.forEachIndexed { i, label ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (page == i) Color.White else Color.Transparent)
                                        .clickable { page = i }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                ) {
                                    Text(label, color = if (page == i) Ink else Subtle, fontSize = 14.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            when (page) {
                                0 -> GeneralSettings(state)
                                1 -> ProviderSettings(state, scope)
                                2 -> AssistantSettings(state)
                                else -> WorkspaceSettings(state)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralSettings(state: AppState) {
    Text("主题", fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    val themes = listOf("浅色", "深色", "跟随系统")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        themes.forEachIndexed { i, t ->
            FilterChip(selected = i == 0, onClick = {}, label = { Text(t) })
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("消息下方提示：联网搜索与思考显示可在对话页开关。", fontSize = 12.sp, color = Subtle)
    Spacer(Modifier.height(20.dp))
    Text("联网搜索", fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    SearchSettings(state)
}

@Composable
private fun SearchSettings(state: AppState) {
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
    TextButton(onClick = { state.updateSettings(state.settings.copy(searchProvider = provider, searchApiKey = key)) }) {
        Text("保存")
    }
}

@Composable
private fun ProviderSettings(state: AppState, scope: CoroutineScope) {
    val provider = state.selectedProvider
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("模型服务", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.weight(1f))
        TextButton(onClick = { state.addProvider(ProviderSetting.OpenAI(name = "新服务", baseUrl = "https://api.openai.com/v1")) }) {
            Text("＋ 添加")
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.providers.forEach { p ->
            FilterChip(selected = p.id == state.selectedProviderId, onClick = { state.selectProvider(p.id) }, label = { Text(p.name) })
        }
    }
    Spacer(Modifier.height(8.dp))
    if (provider == null) {
        Text("还没有服务。", fontSize = 12.sp, color = Subtle)
        return
    }
    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrlValue()) }
    var apiKey by remember(provider.id) { mutableStateOf(provider.apiKeyValue()) }
    OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { state.updateProvider(provider.withNameUrlKey(name, baseUrl, apiKey)) }) { Text("保存") }
        TextButton(onClick = { state.fetchModels(scope, provider) }) { Text("拉取模型") }
    }
    Spacer(Modifier.height(4.dp))
    Text("模型（${provider.models.size}）", fontSize = 12.sp, color = Subtle)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        provider.models.forEach { m ->
            FilterChip(selected = m.id == state.selectedModelId, onClick = { state.selectModel(m.id) }, label = { Text(m.displayName.ifBlank { m.modelId }) })
        }
    }
}

@Composable
private fun AssistantSettings(state: AppState) {
    val assistant = state.currentAssistant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("助手", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.weight(1f))
        TextButton(onClick = { state.addAssistant() }) { Text("＋ 添加") }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.assistants.forEach { a ->
            FilterChip(selected = a.id == state.selectedAssistantId, onClick = { state.selectAssistant(a.id) }, label = { Text(a.name) })
        }
    }
    Spacer(Modifier.height(8.dp))
    if (assistant == null) return
    var name by remember(assistant.id) { mutableStateOf(assistant.name) }
    var prompt by remember(assistant.id) { mutableStateOf(assistant.systemPrompt) }
    var temp by remember(assistant.id) { mutableStateOf(assistant.temperature?.toString() ?: "") }
    OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(prompt, { prompt = it }, label = { Text("系统提示词") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(temp, { temp = it }, label = { Text("温度（可选，0~2）") }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Text("模型：${state.selectedProvider?.name ?: "跟随全局"}", fontSize = 12.sp, color = Subtle)
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = {
        state.updateAssistant(assistant.copy(name = name.ifBlank { "助手" }, systemPrompt = prompt, temperature = temp.toFloatOrNull()))
    }) { Text("保存") }
}

@Composable
private fun WorkspaceSettings(state: AppState) {
    Text("本地工作区（AI 文件操作）", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
    Spacer(Modifier.height(8.dp))
    var dir by remember { mutableStateOf(state.settings.workspaceDir) }
    var allow by remember { mutableStateOf(state.settings.allowCommands) }
    OutlinedTextField(dir, { dir = it }, label = { Text("工作区根目录（绝对路径）") }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("允许 AI 执行 shell 命令", modifier = Modifier.weight(1f), fontSize = 14.sp, color = Ink)
        Switch(checked = allow, onCheckedChange = { allow = it })
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { state.updateSettings(state.settings.copy(workspaceDir = dir, allowCommands = allow)) }) { Text("保存") }
}

private fun ProviderSetting.baseUrlValue(): String = when (this) {
    is ProviderSetting.OpenAI -> baseUrl
    is ProviderSetting.Google -> baseUrl
    is ProviderSetting.Claude -> baseUrl
}

private fun ProviderSetting.apiKeyValue(): String = when (this) {
    is ProviderSetting.OpenAI -> apiKey
    is ProviderSetting.Google -> apiKey
    is ProviderSetting.Claude -> apiKey
}

private fun ProviderSetting.withNameUrlKey(name: String, url: String, key: String): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(name = name, baseUrl = url, apiKey = key)
    is ProviderSetting.Google -> copy(name = name, baseUrl = url, apiKey = key)
    is ProviderSetting.Claude -> copy(name = name, baseUrl = url, apiKey = key)
}

private fun groupConversations(list: List<Conversation>): List<Pair<String, List<Conversation>>> {
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    fun dateOf(c: Conversation) = Instant.ofEpochMilli(c.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val buckets = linkedMapOf("今天" to mutableListOf<Conversation>(), "昨天" to mutableListOf<Conversation>(), "更早" to mutableListOf<Conversation>())
    list.forEach { c ->
        val d = dateOf(c)
        when {
            d == today -> buckets["今天"]!!.add(c)
            d == yesterday -> buckets["昨天"]!!.add(c)
            else -> buckets["更早"]!!.add(c)
        }
    }
    return buckets.filter { it.value.isNotEmpty() }.map { it.key to it.value }
}
