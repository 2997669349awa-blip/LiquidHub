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
    val messages: List<ChatMessage> = emptyList(),
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
    val selectedProviderId: Uuid? = null,
    val selectedModelId: Uuid? = null,
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
