// Modified by AI Hello World on 2026-09-26.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// Scans the local likkahub terminal AI (an Ollama instance on the loopback).

package me.rerere.rikkahub.data.ai.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import java.net.HttpURLConnection
import java.net.URL

/**
 * 探测终端里的 likkahub 本地AI（Termux 中的 Ollama）。
 *
 * Android 各 App 共享同一个 loopback，Termux 里的 `ollama serve` 监听在
 * 127.0.0.1:11434，因此直接访问它的 HTTP API 即可，无需读取 Termux 的私有目录。
 */
object LocalAiScanner {
    const val DEFAULT_BASE_URL = "http://127.0.0.1:11434"

    /** 返回本机 Ollama 的模型名列表；连不上或没有模型时返回空列表。 */
    suspend fun listModels(
        baseUrl: String = DEFAULT_BASE_URL,
        timeoutMillis: Int = 800,
    ): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL("$baseUrl/api/tags").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                requestMethod = "GET"
                setRequestProperty("User-Agent", "LiquidHub/0.1")
                instanceFollowRedirects = true
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching emptyList()
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                JsonInstant.parseToJsonElement(body)
                    .jsonObject["models"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(emptyList())
    }
}
