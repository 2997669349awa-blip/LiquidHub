package me.rerere.ai.provider

<<<<<<< HEAD:core/src/jvmCommonMain/kotlin/me/rerere/ai/provider/ProviderManager.kt
=======
import android.content.Context
import me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider
>>>>>>> upstream/master:ai/src/main/java/me/rerere/ai/provider/ProviderManager.kt
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.grok.GrokProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.OkHttpClient
import java.io.File

/**
 * Provider管理器，负责注册和获取Provider实例
 */
<<<<<<< HEAD:core/src/jvmCommonMain/kotlin/me/rerere/ai/provider/ProviderManager.kt
class ProviderManager(client: OkHttpClient, cacheDir: File? = null) {
=======
class ProviderManager(
    client: OkHttpClient,
    context: Context,
    accountCredentials: AccountCredentialProvider,
) {
>>>>>>> upstream/master:ai/src/main/java/me/rerere/ai/provider/ProviderManager.kt
    // 存储已注册的Provider实例
    private val providers = mutableMapOf<String, Provider<*>>()

    init {
        // 注册默认Provider
<<<<<<< HEAD:core/src/jvmCommonMain/kotlin/me/rerere/ai/provider/ProviderManager.kt
        registerProvider("openai", OpenAIProvider(client, cacheDir))
        registerProvider("google", GoogleProvider(client, cacheDir))
        registerProvider("claude", ClaudeProvider(client, cacheDir))
=======
        registerProvider("openai", OpenAIProvider(client, context))
        registerProvider("google", GoogleProvider(client, context))
        registerProvider("claude", ClaudeProvider(client, context))
        registerProvider("chatgpt", ChatGPTProvider(client, accountCredentials))
        registerProvider("grok", GrokProvider(client, accountCredentials))
>>>>>>> upstream/master:ai/src/main/java/me/rerere/ai/provider/ProviderManager.kt
    }

    /**
     * 注册Provider实例
     *
     * @param name Provider名称
     * @param provider Provider实例
     */
    fun registerProvider(name: String, provider: Provider<*>) {
        providers[name] = provider
    }

    /**
     * 获取Provider实例
     *
     * @param name Provider名称
     * @return Provider实例，如果不存在则返回null
     */
    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     *
     * @param setting Provider设置
     * @return Provider实例，如果不存在则返回null
     */
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude -> getProvider("claude")
            is ProviderSetting.ChatGPT -> getProvider("chatgpt")
            is ProviderSetting.Grok -> getProvider("grok")
        } as Provider<T>
    }
}
