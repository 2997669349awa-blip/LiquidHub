// Modified by AI Hello World on 2026-10-04.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 音乐源插件：用 QuickJS 运行用户添加的 index.js，提供 search/songUrl/lyrics；未启用时回退内置网易云。

package me.rerere.rikkahub.data.music

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.alias.func
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

interface MusicSource {
    val name: String
    suspend fun search(keyword: String): List<MusicSong>
    suspend fun songUrl(id: String): String?
    suspend fun lyrics(id: String): MusicLyrics

    /** 音乐源是否支持「喜欢」。 */
    suspend fun supportsLikes(): Boolean = false
    suspend fun liked(): List<MusicSong> = emptyList()
    suspend fun like(id: String, like: Boolean): Boolean = false
}

data class MusicSong(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val cover: String = "",
)

/** 歌词：原文 + 翻译（可为空）。 */
data class MusicLyrics(
    val lyric: String,
    val translation: String,
)

@Serializable
data class MusicSourceManifest(
    val name: String = "",
    val version: String = "",
    val type: String = "",
    val main: String = "index.js",
)

data class MusicSourceInfo(val name: String, val valid: Boolean)

object MusicSourceStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun root(context: Context): File = File(context.filesDir, "music_sources").apply { mkdirs() }

    fun dir(context: Context, name: String): File? {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        if (safe.isBlank()) return null
        return File(root(context), safe)
    }

    fun list(context: Context): List<MusicSourceInfo> =
        root(context).listFiles()?.filter { it.isDirectory }?.map {
            MusicSourceInfo(it.name, isValid(it))
        }?.sortedBy { it.name } ?: emptyList()

    fun create(context: Context, name: String): File? {
        val dir = dir(context, name) ?: return null
        dir.mkdirs()
        val manifest = File(dir, "manifest.json")
        if (!manifest.exists()) {
            manifest.writeText(
                """{"name":"${name.trim()}","version":"1.0.0","type":"music","main":"index.js"}"""
            )
        }
        return dir
    }

    fun writeFile(context: Context, name: String, fileName: String, bytes: ByteArray): Boolean {
        val dir = dir(context, name) ?: return false
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, File(fileName).name)
        return runCatching { target.writeBytes(bytes); true }.getOrDefault(false)
    }

    fun files(context: Context, name: String): List<String> =
        dir(context, name)?.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    fun delete(context: Context, name: String): Boolean =
        dir(context, name)?.deleteRecursively() ?: false

    fun readManifest(dir: File): MusicSourceManifest? = runCatching {
        json.decodeFromString<MusicSourceManifest>(File(dir, "manifest.json").readText())
    }.getOrNull()

    private fun isValid(dir: File): Boolean {
        val manifest = readManifest(dir) ?: return false
        if (manifest.type != "music") return false
        return File(dir, manifest.main.ifBlank { "index.js" }).isFile
    }
}

object MusicSourceRegistry {
    private const val PREFS = "liquidhub_music_source_active"
    private const val KEY = "active"

    @Volatile
    private var active: MusicSource? = null

    private val _state = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val state: kotlinx.coroutines.flow.StateFlow<String?> = _state.asStateFlow()

    fun init(context: Context) {
        val name = activeName(context) ?: return
        active = load(context, name)
        _state.value = name
    }

    fun current(): MusicSource? = active

    fun activeName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun setActive(context: Context, name: String?): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (name.isNullOrBlank()) {
            prefs.edit().remove(KEY).apply()
            active = null
            _state.value = null
            return true
        }
        val source = load(context, name) ?: return false
        prefs.edit().putString(KEY, name).apply()
        active = source
        _state.value = name
        return true
    }

    fun load(context: Context, name: String): MusicSource? {
        val dir = MusicSourceStore.dir(context, name) ?: return null
        val manifest = MusicSourceStore.readManifest(dir) ?: return null
        if (manifest.type != "music") return null
        return JsMusicSource(name, dir, manifest.main.ifBlank { "index.js" })
    }
}

/** 运行用户 index.js 的音乐源；index.js 需定义全局 search(keyword)/songUrl(id)/lyrics(id)。 */
class JsMusicSource(
    override val name: String,
    private val dir: File,
    private val mainFile: String,
) : MusicSource {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun httpSync(method: String, url: String, headersJson: String, body: String): String {
        return runCatching {
            val builder = Request.Builder().url(url)
            if (headersJson.isNotBlank()) {
                runCatching {
                    json.parseToJsonElement(headersJson).jsonObject.forEach { (k, v) ->
                        builder.header(k, v.jsonPrimitive.contentOrNull.orEmpty())
                    }
                }
            }
            when (method.uppercase()) {
                "POST" -> builder.post(body.toRequestBody("application/json".toMediaType()))
                else -> builder.get()
            }
            client.newCall(builder.build()).execute().use { it.body?.string().orEmpty() }
        }.getOrElse { """{"error":"${it.message?.replace("\"", "'")}"}""" }
    }

    private suspend fun <T> withQuickJs(block: suspend (QuickJs) -> T): T {
        val quickJs = QuickJs.create(Dispatchers.Default)
        try {
            quickJs.func("__http") { args: Array<Any?> ->
                httpSync(
                    args.getOrNull(0)?.toString().orEmpty(),
                    args.getOrNull(1)?.toString().orEmpty(),
                    args.getOrNull(2)?.toString().orEmpty(),
                    args.getOrNull(3)?.toString().orEmpty(),
                )
            }
            quickJs.func("__aesCbcBase64") { args: Array<Any?> ->
                aesCbcBase64(
                    args.getOrNull(0)?.toString().orEmpty(),
                    args.getOrNull(1)?.toString().orEmpty(),
                    args.getOrNull(2)?.toString().orEmpty(),
                )
            }
            quickJs.func("__rsaEncryptHex") { args: Array<Any?> ->
                rsaEncryptHex(
                    args.getOrNull(0)?.toString().orEmpty(),
                    args.getOrNull(1)?.toString().orEmpty(),
                    args.getOrNull(2)?.toString().orEmpty(),
                )
            }
            quickJs.evaluate<Any?>(File(dir, mainFile).readText(), mainFile)
            return block(quickJs)
        } finally {
            quickJs.close()
        }
    }

    private fun aesCbcBase64(text: String, key: String, iv: String): String = runCatching {
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            javax.crypto.spec.IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        android.util.Base64.encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
    }.getOrDefault("")

    private fun rsaEncryptHex(text: String, modulusHex: String, expHex: String): String = runCatching {
        val reversed = java.math.BigInteger(1, text.toByteArray(Charsets.UTF_8).reversedArray())
        val enc = reversed.modPow(java.math.BigInteger(expHex, 16), java.math.BigInteger(modulusHex, 16))
        enc.toString(16).padStart(256, '0')
    }.getOrDefault("")

    /** 校验 index.js：必须有 search/songUrl，且实际搜索能返回结果（搜不到结果视为不可用）。 */
    suspend fun validate(): Boolean = runCatching {
        withQuickJs { quickJs ->
            val hasFns = quickJs.evaluate<Any?>(
                "typeof search === 'function' && typeof songUrl === 'function'"
            ) == true
            if (!hasFns) return@withQuickJs false
            val sample = quickJs.evaluate<Any?>("search(${JsonPrimitive("hello")})")?.toString().orEmpty()
            parseSongs(sample).isNotEmpty()
        }
    }.getOrDefault(false)

    override suspend fun search(keyword: String): List<MusicSong> = runCatching {
        withQuickJs { quickJs ->
            parseSongs(quickJs.evaluate<Any?>("search(${JsonPrimitive(keyword)})").toString())
        }
    }.getOrDefault(emptyList())

    override suspend fun songUrl(id: String): String? = runCatching {
        withQuickJs { quickJs -> quickJs.evaluate<Any?>("songUrl(${JsonPrimitive(id)})")?.toString() }
    }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

    override suspend fun lyrics(id: String): MusicLyrics = runCatching {
        val raw = withQuickJs { quickJs -> quickJs.evaluate<Any?>("lyrics(${JsonPrimitive(id)})")?.toString() }.orEmpty()
        if (raw.trimStart().startsWith("{")) {
            val o = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            MusicLyrics(
                lyric = o?.get("lyric")?.jsonPrimitive?.contentOrNull.orEmpty(),
                translation = o?.get("translation")?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        } else {
            MusicLyrics(lyric = raw, translation = "")
        }
    }.getOrDefault(MusicLyrics("", ""))

    override suspend fun supportsLikes(): Boolean = runCatching {
        withQuickJs { quickJs ->
            quickJs.evaluate<Any?>("typeof liked === 'function' && typeof like === 'function'") == true
        }
    }.getOrDefault(false)

    override suspend fun liked(): List<MusicSong> = runCatching {
        withQuickJs { quickJs ->
            parseSongs(quickJs.evaluate<Any?>("liked()")?.toString().orEmpty())
        }
    }.getOrDefault(emptyList())

    override suspend fun like(id: String, like: Boolean): Boolean = runCatching {
        withQuickJs { quickJs ->
            quickJs.evaluate<Any?>(
                "typeof like === 'function' && like(${JsonPrimitive(id)}, ${if (like) "true" else "false"}) === true"
            ) == true
        }
    }.getOrDefault(false)

    private fun parseSongs(raw: String): List<MusicSong> {
        if (raw.isBlank() || raw == "null") return emptyList()
        val array = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val o = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            MusicSong(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                artist = o["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                album = o["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                cover = o["cover"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }
}

/** 统一入口：只使用已启用的音乐源；未启用时不提供任何音乐接口。 */
object MusicSources {
    suspend fun search(keyword: String): List<MusicSong> =
        MusicSourceRegistry.current()?.search(keyword) ?: emptyList()

    suspend fun songUrl(id: String): String? =
        MusicSourceRegistry.current()?.songUrl(id)

    suspend fun lyrics(id: String): MusicLyrics =
        MusicSourceRegistry.current()?.lyrics(id) ?: MusicLyrics("", "")

    suspend fun supportsLikes(): Boolean =
        MusicSourceRegistry.current()?.supportsLikes() ?: false

    suspend fun liked(): List<MusicSong> =
        MusicSourceRegistry.current()?.liked() ?: emptyList()

    suspend fun like(id: String, like: Boolean): Boolean =
        MusicSourceRegistry.current()?.like(id, like) ?: false
}
