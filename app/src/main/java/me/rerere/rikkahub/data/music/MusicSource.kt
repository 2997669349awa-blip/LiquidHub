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
}

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
            quickJs.evaluate<Any?>(File(dir, mainFile).readText(), mainFile)
            return block(quickJs)
        } finally {
            quickJs.close()
        }
    }

    /** 校验 index.js 是否提供了必需函数。 */
    suspend fun validate(): Boolean = runCatching {
        withQuickJs { quickJs ->
            quickJs.evaluate<Any?>("typeof search === 'function' && typeof songUrl === 'function'") == true
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
        val text = withQuickJs { quickJs -> quickJs.evaluate<Any?>("lyrics(${JsonPrimitive(id)})")?.toString() }.orEmpty()
        MusicLyrics(lyric = text, translation = "")
    }.getOrDefault(MusicLyrics("", ""))

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

/** 统一入口：有启用的音乐源就用它，否则回退内置网易云。 */
object MusicSources {
    suspend fun search(keyword: String): List<MusicSong> =
        MusicSourceRegistry.current()?.search(keyword) ?: NeteaseApi.search(keyword)

    suspend fun songUrl(id: String): String? =
        MusicSourceRegistry.current()?.songUrl(id) ?: NeteaseApi.songUrl(id)

    suspend fun lyrics(id: String): MusicLyrics =
        MusicSourceRegistry.current()?.lyrics(id) ?: NeteaseApi.lyrics(id)
}
