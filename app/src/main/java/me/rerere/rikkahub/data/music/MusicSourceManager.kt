// Modified by AI Hello World on 2026-10-04.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 音乐源验证：本地生成加密包，输入 32 位密码解密出 NetEaseCloudMusic.LiquidHub 后启用内置音乐。

package me.rerere.rikkahub.data.music

import android.content.Context
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MusicSourceManager {
    const val MARKER = "NeteaseCloudMusic.LiquidHub"
    const val TRIGGER = "LiquidHub"

    private const val PREFS = "liquidhub_music_source"
    private const val KEY_FLAG = "unlocked"
    private const val ALGO = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    // 32 位密码的 SHA-256（作为 AES-256 密钥；不保存明文密码）
    private const val PASSWORD_SHA256 =
        "75159a930ac6cf22bb2e4b18bc316062cdcb14151b9e7813229b0d00c7e68919"

    private val _state = MutableStateFlow(false)
    val state: StateFlow<Boolean> = _state.asStateFlow()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** App 启动时加载解锁状态。 */
    fun init(context: Context) {
        _state.value = prefs(context).getBoolean(KEY_FLAG, false)
    }

    fun isUnlocked(context: Context): Boolean = prefs(context).getBoolean(KEY_FLAG, false)

    fun isUnlockedCached(): Boolean = _state.value

    fun generateArchive(context: Context): File? = runCatching {
        val key = SecretKeySpec(hexToBytes(PASSWORD_SHA256), "AES")
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(MARKER.toByteArray(Charsets.UTF_8))
        val file = archiveFile(context)
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            out.write(iv.size)
            out.write(iv)
            out.write(cipherText)
        }
        file
    }.getOrNull()

    /** 输入 32 位密码：解密成功（得到标记）即启用音乐。 */
    fun unlock(context: Context, password: String): Boolean = runCatching {
        val file = archiveFile(context)
        if (!file.exists()) return false
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return false
        val ivLen = bytes[0].toInt()
        if (ivLen <= 0 || 1 + ivLen >= bytes.size) return false
        val iv = bytes.copyOfRange(1, 1 + ivLen)
        val cipherText = bytes.copyOfRange(1 + ivLen, bytes.size)
        val key = SecretKeySpec(sha256(password.trim()), "AES")
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plain = String(cipher.doFinal(cipherText), Charsets.UTF_8)
        if (plain == MARKER) {
            prefs(context).edit().putBoolean(KEY_FLAG, true).apply()
            _state.value = true
            true
        } else {
            false
        }
    }.getOrDefault(false)

    fun archiveFile(context: Context): File =
        File(File(context.filesDir, "music_source"), "$MARKER.enc")

    private fun sha256(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
