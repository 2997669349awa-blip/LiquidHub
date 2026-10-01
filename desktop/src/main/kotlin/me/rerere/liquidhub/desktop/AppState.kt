package me.rerere.liquidhub.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 桌面端顶层状态：会话、当前会话、设置。持久化到 ~/.liquidhub/state.json。
 */
class AppState {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val storeFile = File(System.getProperty("user.home"), ".liquidhub/state.json")

    val conversations = mutableStateListOf<Conversation>()
    var currentId by mutableStateOf<String?>(null)
    var settings by mutableStateOf(AppSettings())
    var sending by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

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
            message.content.take(20)
        } else {
            old.title
        }
        conversations[i] = old.copy(title = newTitle, messages = old.messages + message)
        save()
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
                val history = conversations.firstOrNull { it.id == conversation.id }?.messages.orEmpty()
                val reply = OpenAiClient.chat(settings, history)
                appendMessage(conversation.id, ChatMessage("assistant", reply))
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally {
                sending = false
            }
        }
    }
}
