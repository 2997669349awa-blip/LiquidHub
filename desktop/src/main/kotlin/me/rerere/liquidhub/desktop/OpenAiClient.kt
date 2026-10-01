package me.rerere.liquidhub.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 极简的 OpenAI 兼容 Chat Completions 客户端（非流式）。
 * 先覆盖远程 Provider；本地模型（Ollama/LM Studio/llama.cpp server）只要暴露兼容端点即可直接用。
 */
object OpenAiClient {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun chat(settings: AppSettings, messages: List<ChatMessage>): String =
        withContext(Dispatchers.IO) {
            if (settings.apiKey.isBlank()) error("尚未配置 API Key，请在「设置」里填写")
            val url = settings.baseUrl.trimEnd('/') + "/chat/completions"
            val payload = buildJsonObject {
                put("model", settings.model)
                put("stream", false)
                putJsonArray("messages") {
                    for (m in messages) {
                        addJsonObject {
                            put("role", m.role)
                            put("content", m.content)
                        }
                    }
                }
            }
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${settings.apiKey}")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                error("HTTP ${response.statusCode()}: ${response.body().take(500)}")
            }
            val root = json.parseToJsonElement(response.body()).jsonObject
            root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: "(空响应)"
        }
}
