package me.rerere.liquidhub.desktop

import kotlinx.serialization.Serializable

@Serializable
data class ToolCallFunction(val name: String, val arguments: String = "{}")

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val title: String = "新对话",
    val messages: List<ChatMessage> = emptyList(),
)

@Serializable
data class AppSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
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
    val conversations: List<Conversation> = emptyList(),
    val currentId: String? = null,
)
