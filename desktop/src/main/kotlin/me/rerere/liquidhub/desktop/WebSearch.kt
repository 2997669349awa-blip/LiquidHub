package me.rerere.liquidhub.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 联网搜索：Tavily / Exa，返回可直接注入提示词的文本。 */
object WebSearch {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(settings: AppSettings, query: String): String = withContext(Dispatchers.IO) {
        if (settings.searchApiKey.isBlank()) return@withContext ""
        runCatching {
            when (settings.searchProvider.lowercase()) {
                "exa" -> exa(settings.searchApiKey, query)
                else -> tavily(settings.searchApiKey, query)
            }
        }.getOrElse { "搜索失败：${it.message}" }
    }

    private fun post(url: String, headers: Map<String, String>, body: JsonObject): String {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }
        return response.body()
    }

    private fun tavily(key: String, query: String): String {
        val body = buildJsonObject {
            put("api_key", key)
            put("query", query)
            put("max_results", 5)
            put("search_depth", "basic")
        }
        val root = json.parseToJsonElement(post("https://api.tavily.com/search", emptyMap(), body)).jsonObject
        val results = root["results"]?.jsonArray ?: return ""
        return results.take(5).mapIndexed { i, r ->
            val o = r.jsonObject
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: ""
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: ""
            val content = o["content"]?.jsonPrimitive?.contentOrNull ?: ""
            "${i + 1}. $title\n$url\n$content"
        }.joinToString("\n\n")
    }

    private fun exa(key: String, query: String): String {
        val body = buildJsonObject {
            put("query", query)
            put("numResults", 5)
            putJsonObject("contents") { put("text", true) }
        }
        val root = json.parseToJsonElement(
            post("https://api.exa.ai/search", mapOf("x-api-key" to key), body)
        ).jsonObject
        val results = root["results"]?.jsonArray ?: return ""
        return results.take(5).mapIndexed { i, r ->
            val o = r.jsonObject
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: ""
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: ""
            val text = o["text"]?.jsonPrimitive?.contentOrNull ?: ""
            "${i + 1}. $title\n$url\n${text.take(800)}"
        }.joinToString("\n\n")
    }
}
