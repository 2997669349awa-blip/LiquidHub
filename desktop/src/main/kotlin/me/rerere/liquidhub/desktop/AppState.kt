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
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid

/** 桌面端顶层状态。持久化到 ~/.liquidhub/state.json。 */
class AppState {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val home = File(System.getProperty("user.home"), ".liquidhub").apply { mkdirs() }
    private val storeFile = File(home, "state.json")

    private val core = CoreProviders(File(home, "cache").apply { mkdirs() })

    val conversations = mutableStateListOf<Conversation>()
    val providers = mutableStateListOf<ProviderSetting>()

    var currentId by mutableStateOf<String?>(null)
    var selectedProviderId by mutableStateOf<Uuid?>(null)
    var selectedModelId by mutableStateOf<Uuid?>(null)
    var settings by mutableStateOf(AppSettings())
    var sending by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val current: Conversation? get() = conversations.firstOrNull { it.id == currentId }
    val selectedProvider: ProviderSetting? get() = providers.firstOrNull { it.id == selectedProviderId }
    val selectedModel: Model?
        get() {
            val p = selectedProvider ?: return null
            return p.models.firstOrNull { it.id == selectedModelId } ?: p.models.firstOrNull()
        }

    fun load() {
        runCatching {
            if (storeFile.isFile) {
                val s = json.decodeFromString<PersistedState>(storeFile.readText())
                conversations.clear()
                conversations.addAll(s.conversations)
                providers.clear()
                providers.addAll(if (s.providers.isEmpty()) DEFAULT_DESKTOP_PROVIDERS else s.providers)
                settings = s.settings
                currentId = s.currentId
                selectedProviderId = s.selectedProviderId
                selectedModelId = s.selectedModelId
            }
        }
        if (providers.isEmpty()) providers.addAll(DEFAULT_DESKTOP_PROVIDERS)
        if (conversations.isEmpty()) newConversation()
        if (currentId == null) currentId = conversations.firstOrNull()?.id
        if (selectedProviderId == null) selectedProviderId = providers.firstOrNull()?.id
        if (selectedModelId == null) selectedModelId = selectedProvider?.models?.firstOrNull()?.id
    }

    fun save() {
        runCatching {
            storeFile.writeText(
                json.encodeToString(
                    PersistedState(
                        settings = settings,
                        providers = providers.toList(),
                        selectedProviderId = selectedProviderId,
                        selectedModelId = selectedModelId,
                        conversations = conversations.toList(),
                        currentId = currentId,
                    )
                )
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

    fun selectProvider(id: Uuid) {
        selectedProviderId = id
        selectedModelId = providers.firstOrNull { it.id == id }?.models?.firstOrNull()?.id
        save()
    }

    fun selectModel(id: Uuid) {
        selectedModelId = id
        save()
    }

    fun addProvider(setting: ProviderSetting) {
        providers.add(setting)
        save()
    }

    fun updateProvider(setting: ProviderSetting) {
        val i = providers.indexOfFirst { it.id == setting.id }
        if (i >= 0) {
            providers[i] = setting
            save()
        }
    }

    fun updateSettings(s: AppSettings) {
        settings = s
        save()
    }

    /** 从服务端拉取模型列表。 */
    fun fetchModels(scope: CoroutineScope, setting: ProviderSetting) {
        scope.launch {
            error = null
            try {
                val list = core.listModels(setting)
                val updated = setting.copyProvider(models = list)
                updateProvider(updated)
                if (selectedProviderId == setting.id) {
                    selectedModelId = list.firstOrNull()?.id ?: selectedModelId
                    save()
                }
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            }
        }
    }

    fun send(scope: CoroutineScope, text: String) {
        val conversation = current ?: return
        val provider = selectedProvider
        val model = selectedModel
        val content = text.trim()
        if (content.isEmpty() || sending) return
        if (provider == null) {
            error = "请先在「设置」里添加并选择一个模型服务"
            return
        }
        if (model == null) {
            error = "该服务下没有模型，请在「设置」里点『拉取模型』"
            return
        }
        appendMessage(conversation.id, ChatMessage("user", content))
        appendMessage(conversation.id, ChatMessage("assistant", ""))
        sending = true
        error = null
        scope.launch {
            try {
                val history = conversations.firstOrNull { it.id == conversation.id }?.messages.orEmpty()
                val searchContext = if (settings.webSearch && settings.searchApiKey.isNotBlank()) {
                    WebSearch.search(settings, content)
                } else ""
                val request = if (searchContext.isBlank()) {
                    history
                } else {
                    listOf(
                        ChatMessage(
                            "system",
                            "以下是实时联网搜索结果，请结合它回答：\n$searchContext",
                        )
                    ) + history
                }
                core.stream(
                    setting = provider,
                    history = request,
                    model = model,
                    onText = { delta -> withContext(Dispatchers.Main) { appendDelta(conversation.id, delta) } },
                    onReasoning = { delta -> withContext(Dispatchers.Main) { appendReasoning(conversation.id, delta) } },
                )
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally {
                sending = false
                save()
            }
        }
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
        if (last != null && last.role == "assistant") {
            msgs[msgs.size - 1] = last.copy(content = (last.content ?: "") + delta)
        } else {
            msgs.add(ChatMessage("assistant", delta))
        }
        conversations[i] = old.copy(messages = msgs)
    }

    private fun appendReasoning(conversationId: String, delta: String) {
        val i = conversations.indexOfFirst { it.id == conversationId }
        if (i < 0) return
        val old = conversations[i]
        val msgs = old.messages.toMutableList()
        val last = msgs.lastOrNull()
        if (last != null && last.role == "assistant") {
            msgs[msgs.size - 1] = last.copy(reasoning = (last.reasoning ?: "") + delta)
        } else {
            msgs.add(ChatMessage("assistant", "", delta))
        }
        conversations[i] = old.copy(messages = msgs)
    }
}
