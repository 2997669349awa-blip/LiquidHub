package me.rerere.liquidhub.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** 桌面端顶层状态：会话、当前会话、设置、流式与工具编排。持久化到 ~/.liquidhub/state.json。 */
class AppState {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val storeFile = File(System.getProperty("user.home"), ".liquidhub/state.json")

    val conversations = mutableStateListOf<Conversation>()
    var currentId by mutableStateOf<String?>(null)
    var settings by mutableStateOf(AppSettings())
    var sending by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var toolNotice by mutableStateOf<String?>(null)

    val current: Conversation? get() = conversations.firstOrNull { it.id == currentId }

    fun load() {
        runCatching {
            if (storeFile.isFile) {
                val s = json.decodeFromString<PersistedState>(storeFile.readText())
                conversations.clear()
                conversations.addAll(s.conversations)
                settings = s.settings
                currentId = s.currentId
            }
        }
        if (conversations.isEmpty()) newConversation()
        if (currentId == null) currentId = conversations.firstOrNull()?.id
    }

    fun save() {
        runCatching {
            storeFile.parentFile?.mkdirs()
            storeFile.writeText(
                json.encodeToString(PersistedState(settings, conversations.toList(), currentId))
            )
        }
    }

    fun newConversation(): Conversation {
        val c = Conversation(id = UUID.randomUUID().toString())
        conversations.add(0, c)
        currentId = c.id
        save()
        return c
    }

    fun select(id: String) {
        currentId = id
        save()
    }

    fun appendMessage(conversationId: String, message: ChatMessage) {
        val i = conversations.indexOfFirst { it.id == conversationId }
        if (i < 0) return
        val old = conversations[i]
        val newTitle = if (old.messages.isEmpty() && message.role == "user") {
            (message.content ?: "").take(20)
        } else {
            old.title
        }
        conversations[i] = old.copy(title = newTitle, messages = old.messages + message)
    }

    private fun appendDelta(conversationId: String, delta: String) {
        val i = conversations.indexOfFirst { it.id == conversationId }
        if (i < 0) return
        val old = conversations[i]
        val msgs = old.messages.toMutableList()
        val last = msgs.lastOrNull()
        if (last != null && last.role == "assistant" && last.toolCalls == null) {
            msgs[msgs.size - 1] = last.copy(content = (last.content ?: "") + delta)
        } else {
            msgs.add(ChatMessage("assistant", delta))
        }
        conversations[i] = old.copy(messages = msgs)
    }

    fun updateSettings(s: AppSettings) {
        settings = s
        save()
    }

    fun send(scope: CoroutineScope, text: String) {
        val conversation = current ?: return
        val content = text.trim()
        if (content.isEmpty() || sending) return
        appendMessage(conversation.id, ChatMessage("user", content))
        sending = true
        error = null
        scope.launch {
            try {
                val tools = if (settings.workspaceDir.isNotBlank()) workspaceTools() else null
                val systemContext = buildString {
                    if (settings.workspaceDir.isNotBlank()) {
                        append("你可以操作本地工作区，根目录：${settings.workspaceDir}。")
                        append("可用工具：list_dir / read_file / write_file / run_command")
                        if (!settings.allowCommands) append("（run_command 当前被用户关闭）")
                        append("。需要时先看目录再操作。\n")
                    }
                    if (settings.webSearch && settings.searchApiKey.isNotBlank()) {
                        val results = WebSearch.search(settings, content)
                        if (results.isNotBlank()) {
                            append("\n以下是实时联网搜索结果，请结合它回答：\n")
                            append(results)
                        }
                    }
                }.trim()

                var guard = 0
                while (true) {
                    val history = conversations.firstOrNull { it.id == conversation.id }?.messages.orEmpty()
                    val messages = if (systemContext.isBlank()) {
                        history
                    } else {
                        listOf(ChatMessage("system", systemContext)) + history
                    }
                    val turn = OpenAiClient.streamChat(settings, messages, tools) { delta ->
                        withContext(Dispatchers.Main) { appendDelta(conversation.id, delta) }
                    }
                    if (turn.toolCalls.isEmpty()) break
                    appendMessage(
                        conversation.id,
                        ChatMessage(
                            role = "assistant",
                            content = turn.content.ifEmpty { null },
                            toolCalls = turn.toolCalls,
                        ),
                    )
                    for (call in turn.toolCalls) {
                        toolNotice = "调用工具：${call.function.name}"
                        val output = withContext(Dispatchers.IO) { executeWorkspaceTool(settings, call) }
                        appendMessage(
                            conversation.id,
                            ChatMessage(
                                role = "tool",
                                content = output,
                                toolCallId = call.id,
                                name = call.function.name,
                            ),
                        )
                    }
                    if (guard++ >= 6) break
                }
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally {
                sending = false
                toolNotice = null
                save()
            }
        }
    }
}
