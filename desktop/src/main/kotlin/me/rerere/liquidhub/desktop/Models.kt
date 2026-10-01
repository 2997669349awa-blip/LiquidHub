package me.rerere.liquidhub.desktop

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
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
)

@Serializable
data class PersistedState(
    val settings: AppSettings = AppSettings(),
    val conversations: List<Conversation> = emptyList(),
    val currentId: String? = null,
)
