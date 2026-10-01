package me.rerere.liquidhub.desktop

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val reasoning: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val title: String = "新对话",
    val assistantId: Uuid? = null,
    val messages: List<ChatMessage> = emptyList(),
)

/** 助手：系统提示词 + 可选的模型绑定（对照 RikkaHub 的 Assistant）。 */
@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val name: String = "默认助手",
    val systemPrompt: String = "",
    val providerId: Uuid? = null,
    val modelId: Uuid? = null,
    val temperature: Float? = null,
)

@Serializable
data class AppSettings(
    val webSearch: Boolean = false,
    /** tavily | exa */
    val searchProvider: String = "tavily",
    val searchApiKey: String = "",
    /** 本地工作区根目录；为空表示未启用工作区 */
    val workspaceDir: String = "",
    /** 允许 AI 在工作区执行 shell 命令（默认关闭，风险更高） */
    val allowCommands: Boolean = false,
)

@Serializable
data class PersistedState(
    val settings: AppSettings = AppSettings(),
    val providers: List<ProviderSetting> = emptyList(),
    val assistants: List<Assistant> = emptyList(),
    val selectedProviderId: Uuid? = null,
    val selectedModelId: Uuid? = null,
    val selectedAssistantId: Uuid? = null,
    val conversations: List<Conversation> = emptyList(),
    val currentId: String? = null,
)

/** 首次运行时的预置 Provider（都走 OpenAI 兼容接口，用户填 Key 即用）。 */
val DEFAULT_DESKTOP_PROVIDERS: List<ProviderSetting> = listOf(
    ProviderSetting.OpenAI(
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        models = listOf(
            Model(modelId = "deepseek-chat", displayName = "deepseek-chat"),
            Model(modelId = "deepseek-reasoner", displayName = "deepseek-reasoner"),
        ),
    ),
    ProviderSetting.OpenAI(
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        models = listOf(
            Model(modelId = "gpt-4o-mini", displayName = "gpt-4o-mini"),
            Model(modelId = "gpt-4o", displayName = "gpt-4o"),
        ),
    ),
)

val DEFAULT_DESKTOP_ASSISTANT = Assistant(
    name = "默认助手",
    systemPrompt = "你是一个乐于助人的中文 AI 助手。",
)
