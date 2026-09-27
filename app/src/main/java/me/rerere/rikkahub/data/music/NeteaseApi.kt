// Modified by AI Hello World on 2026-09-27.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 网易云音乐「逆向」Web 接口客户端（weapi 加密）。仅供个人在自有账号下使用。

package me.rerere.rikkahub.data.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class MusicSong(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val cover: String = "",
)

data class MusicUser(
    val uid: String,
    val nickname: String,
    val vip: Boolean,
)

object NeteaseApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    private const val BASE_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val PUB_MOD =
        "e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4" +
            "ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d8" +
            "13cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val PUB_EXP = "010001"
    private const val CHARSET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

    /** 已登录的 Cookie（MUSIC_U 等），由调用方持久化。 */
    @Volatile var cookie: String = ""

    private fun aesEncrypt(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8)),
        )
        return android.util.Base64.encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
    }

    private fun weapi(payload: JsonObject): Pair<String, String> {
        val secret = buildString { repeat(16) { append(CHARSET[random.nextInt(CHARSET.length)]) } }
        val text = payload.toString()
        val first = aesEncrypt(text, BASE_KEY)
        val params = aesEncrypt(first, secret)
        val reversed = secret.toByteArray(Charsets.UTF_8).reversedArray()
        val big = BigInteger(1, reversed)
        val enc = big.modPow(BigInteger(PUB_EXP, 16), BigInteger(PUB_MOD, 16)).toString(16).padStart(256, '0')
        return params to enc
    }

    private fun post(path: String, payload: JsonObject): JsonElement? {
        val (params, encSecKey) = weapi(payload)
        val body = "params=${URLEncoder.encode(params, "UTF-8")}&encSecKey=${URLEncoder.encode(encSecKey, "UTF-8")}"
        return request("https://music.163.com$path", body)
    }

    private fun request(url: String, body: String?): JsonElement? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = if (body != null) "POST" else "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://music.163.com/")
            setRequestProperty("Origin", "https://music.163.com")
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            // 捕获 Set-Cookie 用于登录
            conn.headerFields?.get("Set-Cookie")?.forEach { c ->
                val musicU = c.substringBefore(';')
                if (musicU.startsWith("MUSIC_U=") || musicU.startsWith("__csrf=")) {
                    cookie = mergeCookie(cookie, musicU)
                }
            }
            val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: return null
            return runCatching { json.parseToJsonElement(text) }.getOrNull()
        } finally {
            conn.disconnect()
        }
    }

    private fun mergeCookie(existing: String, add: String): String {
        val map = LinkedHashMap<String, String>()
        existing.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
            map[it.substringBefore("=")] = it
        }
        map[add.substringBefore("=")] = add
        return map.values.joinToString("; ")
    }

    // ---- API ----

    fun search(keyword: String, limit: Int = 30): List<MusicSong> {
        val res = post(
            "/weapi/cloudsearch/get/web?csrf_token=",
            buildJsonObject {
                put("s", keyword)
                put("type", 1)
                put("limit", limit)
                put("offset", 0)
                put("total", true)
            },
        ) ?: return emptyList()
        val songs = res.jsonObject["result"]?.jsonObject?.get("songs")?.jsonArray ?: return emptyList()
        return songs.mapNotNull { parseSong(it.jsonObject) }
    }

    private fun parseSong(o: JsonObject): MusicSong? {
        val id = o["id"]?.jsonPrimitive?.content ?: return null
        val name = o["name"]?.jsonPrimitive?.content ?: ""
        val artist = o["ar"]?.jsonArray?.joinToString("/") { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }
            ?: o["artists"]?.jsonArray?.joinToString("/") { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }
            ?: ""
        val album = o["al"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: o["album"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""
        val cover = (o["al"]?.jsonObject?.get("picUrl")?.jsonPrimitive?.content
            ?: o["album"]?.jsonObject?.get("picUrl")?.jsonPrimitive?.content ?: "")
            .replaceFirst("http://", "https://")
        return MusicSong(id, name, artist, album, cover)
    }

    /** 返回可完整播放的音频 URL（非 VIP 一般为标准音质）。 */
    fun songUrl(id: String): String? {
        val res = post(
            "/weapi/song/enhance/player/url/v1?csrf_token=",
            buildJsonObject {
                put("ids", jsonArrayOf(id))
                put("level", "standard")
                put("encodeType", "aac")
            },
        ) ?: return null
        val url = res.jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
        return url?.takeIf { it.isNotBlank() }
    }

    fun lyrics(id: String): String {
        val res = post(
            "/weapi/song/lyric?csrf_token=",
            buildJsonObject {
                put("id", id)
                put("lv", -1)
                put("kv", -1)
                put("tv", -1)
            },
        ) ?: return ""
        return res.jsonObject["lrc"]?.jsonObject?.get("lyric")?.jsonPrimitive?.content.orEmpty()
    }

    /** 当前登录账号（用于拿 uid / VIP）。 */
    fun account(): MusicUser? {
        val res = post("/weapi/w/nuser/account/get?csrf_token=", buildJsonObject { }) ?: return null
        val profile = res.jsonObject["profile"]?.jsonObject ?: return null
        val uidV = profile["userId"]?.jsonPrimitive?.content ?: return null
        val nickname = profile["nickname"]?.jsonPrimitive?.content ?: ""
        val vip = profile["vipType"]?.jsonPrimitive?.content?.toIntOrNull()?.let { it > 0 } ?: false
        return MusicUser(uidV, nickname, vip)
    }

    fun userDetail(uid: String): MusicUser? {        val res = post(
            "/weapi/v1/user/detail/$uid?csrf_token=",
            buildJsonObject { },
        ) ?: return null
        val profile = res.jsonObject["profile"]?.jsonObject ?: return null
        val uidV = profile["userId"]?.jsonPrimitive?.content ?: uid
        val nickname = profile["nickname"]?.jsonPrimitive?.content ?: ""
        val vip = profile["vipType"]?.jsonPrimitive?.content?.toIntOrNull()?.let { it > 0 } ?: false
        return MusicUser(uidV, nickname, vip)
    }

    fun likeList(uid: String): List<MusicSong> {
        // 喜欢的音乐是用户第一个歌单
        val res = post(
            "/weapi/user/playlist?csrf_token=",
            buildJsonObject {
                put("uid", uid)
                put("limit", 1)
                put("offset", 0)
            },
        ) ?: return emptyList()
        val playlistId = res.jsonObject["playlist"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: return emptyList()
        val detail = post(
            "/weapi/v6/playlist/detail?csrf_token=",
            buildJsonObject { put("id", playlistId); put("n", 1000); put("s", 8) },
        ) ?: return emptyList()
        val tracks = detail.jsonObject["playlist"]?.jsonObject?.get("tracks")?.jsonArray ?: return emptyList()
        return tracks.mapNotNull { parseSong(it.jsonObject) }
    }

    fun like(id: String, like: Boolean): Boolean {
        val res = post(
            "/weapi/song/like?csrf_token=",
            buildJsonObject { put("trackId", id); put("like", like) }
        ) ?: return false
        return res.jsonObject["code"]?.jsonPrimitive?.content == "200"
    }

    // ---- 登录 ----
    private val qrJson = Json { ignoreUnknownKeys = true }

    fun qrCreate(): String? {
        val text = qrRequest("https://music.163.com/api/login/qrcode/unikey", "type=1")
        val o = text?.let { runCatching { qrJson.parseToJsonElement(it).jsonObject }.getOrNull() }
        return o?.get("unikey")?.jsonPrimitive?.content
    }

    fun qrPoll(key: String): Int {
        val text = qrRequest("https://music.163.com/api/login/qrcode/client/login", "type=1&key=${URLEncoder.encode(key, "UTF-8")}") ?: return -1
        return runCatching {
            qrJson.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        }.getOrDefault(-1)
    }

    fun cellphoneLogin(phone: String, password: String): Boolean {
        val res = post(
            "/weapi/login/cellphone?csrf_token=",
            buildJsonObject { put("phone", phone); put("password", password); put("countrycode", "86") },
        ) ?: return false
        return res.jsonObject["code"]?.jsonPrimitive?.content == "200"
    }

    private fun qrRequest(url: String, body: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://music.163.com/")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.headerFields?.get("Set-Cookie")?.forEach { c ->
                val kv = c.substringBefore(';')
                if (kv.startsWith("MUSIC_U=") || kv.startsWith("__csrf=")) cookie = mergeCookie(cookie, kv)
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Throwable) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun jsonArrayOf(value: String): JsonElement =
        kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(value)))
}
