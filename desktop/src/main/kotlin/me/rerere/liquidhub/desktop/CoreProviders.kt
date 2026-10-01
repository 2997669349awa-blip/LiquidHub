package me.rerere.liquidhub.desktop

import kotlinx.coroutines.flow.collect
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 复用 :core 的真实 Provider 实现（OpenAI / Google / Claude），桌面端不再自研聊天客户端。
 */
class CoreProviders(cacheDir: File) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    private val manager = ProviderManager(client, cacheDir)

    suspend fun listModels(setting: ProviderSetting): List<Model> = when (setting) {
        is ProviderSetting.OpenAI -> manager.getProviderByType(setting).listModels(setting)
        is ProviderSetting.Google -> manager.getProviderByType(setting).listModels(setting)
        is ProviderSetting.Claude -> manager.getProviderByType(setting).listModels(setting)
    }

    suspend fun stream(
        setting: ProviderSetting,
        history: List<ChatMessage>,
        model: Model,
        onText: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
    ) {
        val messages = history.map { m ->
            when (m.role) {
                "user" -> UIMessage.user(m.content.orEmpty())
                "assistant" -> UIMessage.assistant(m.content.orEmpty())
                else -> UIMessage.system(m.content.orEmpty())
            }
        }
        val params = TextGenerationParams(model = model)
        val flow = when (setting) {
            is ProviderSetting.OpenAI -> manager.getProviderByType(setting).streamText(setting, messages, params)
            is ProviderSetting.Google -> manager.getProviderByType(setting).streamText(setting, messages, params)
            is ProviderSetting.Claude -> manager.getProviderByType(setting).streamText(setting, messages, params)
        }
        flow.collect { chunk ->
            when (chunk) {
                is StreamChunk.TextDelta -> if (chunk.text.isNotEmpty()) onText(chunk.text)
                is StreamChunk.ReasoningDelta -> if (chunk.text.isNotEmpty()) onReasoning(chunk.text)
                else -> {}
            }
        }
    }
}
