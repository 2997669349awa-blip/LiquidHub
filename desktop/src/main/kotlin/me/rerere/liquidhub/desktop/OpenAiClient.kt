package me.rerere.liquidhub.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class AssistantTurn(
    val content: String,
    val toolCalls: List<ToolCall>,
)

private class ToolCallAcc {
    var id: String? = null
    var name: String? = null
    val args = StringBuilder()
}

/**
 * OpenAI 兼容的流式 Chat Completions 客户端，支持 function calling。
 */
object OpenAiClient {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun streamChat(
        settings: AppSettings,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>?,
        onDelta: suspend (String) -> Unit,
    ): AssistantTurn = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank()) error("尚未配置 API Key，请在「设置」里填写")
        val url = settings.baseUrl.trimEnd('/') + "/chat/completions"
        val payload = buildJsonObject {
            put("model", settings.model)
            put("stream", true)
            putJsonArray("messages") { messages.forEach { add(messageJson(it)) } }
            if (!tools.isNullOrEmpty()) {
                putJsonArray("tools") { tools.forEach { add(it.toJson()) } }
            }
        }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            val body = response.body().readBytes().decodeToString()
            error("HTTP ${response.statusCode()}: ${body.take(500)}")
        }
        val content = StringBuilder()
        val toolAcc = LinkedHashMap<Int, ToolCallAcc>()
        response.body().bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                val delta = obj["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("delta")?.jsonObject ?: continue
                delta["content"]?.jsonPrimitive?.contentOrNull?.let {
                    if (it.isNotEmpty()) {
                        content.append(it)
                        onDelta(it)
                    }
                }
                delta["tool_calls"]?.jsonArray?.forEach { t ->
                    val tObj = t.jsonObject
                    val idx = tObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val acc = toolAcc.getOrPut(idx) { ToolCallAcc() }
                    tObj["id"]?.jsonPrimitive?.contentOrNull?.let { acc.id = it }
                    tObj["function"]?.jsonObject?.let { f ->
                        f["name"]?.jsonPrimitive?.contentOrNull?.let { acc.name = it }
                        f["arguments"]?.jsonPrimitive?.contentOrNull?.let { acc.args.append(it) }
                    }
                }
            }
        }
        val calls = toolAcc.values
            .filter { it.name != null }
            .mapIndexed { i, acc ->
                ToolCall(
                    id = acc.id ?: "call_${i}_${acc.name}",
                    function = ToolCallFunction(name = acc.name!!, arguments = acc.args.toString().ifBlank { "{}" }),
                )
            }
        AssistantTurn(content.toString(), calls)
    }

    private fun messageJson(m: ChatMessage): JsonObject = buildJsonObject {
        put("role", m.role)
        if (m.content != null) {
            put("content", m.content)
        } else if (m.toolCalls.isNullOrEmpty()) {
            put("content", "")
        }
        m.toolCallId?.let { put("tool_call_id", it) }
        m.name?.let { put("name", it) }
        m.toolCalls?.let { calls ->
            putJsonArray("tool_calls") {
                calls.forEach { tc ->
                    addJsonObject {
                        put("id", tc.id)
                        put("type", tc.type)
                        putJsonObject("function") {
                            put("name", tc.function.name)
                            put("arguments", tc.function.arguments)
                        }
                    }
                }
            }
        }
    }
}
