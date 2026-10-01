package me.rerere.ai.util

import me.rerere.ai.ui.UIMessagePart

data class EncodedImage(
    val base64: String,
    val mimeType: String,
)

/**
 * 把图片编码为 base64。
 * Android 端会按需压缩并用 EXIF 纠正方向；桌面(JVM)端直接读取文件字节。
 */
expect fun UIMessagePart.Image.encodeBase64(withPrefix: Boolean): Result<EncodedImage>

/** 便捷重载：默认带 data URL 前缀。 */
fun UIMessagePart.Image.encodeBase64(): Result<EncodedImage> = encodeBase64(true)

expect fun UIMessagePart.Video.encodeBase64(withPrefix: Boolean): Result<String>

fun UIMessagePart.Video.encodeBase64(): Result<String> = encodeBase64(true)

expect fun UIMessagePart.Audio.encodeBase64(withPrefix: Boolean): Result<String>

fun UIMessagePart.Audio.encodeBase64(): Result<String> = encodeBase64(true)
