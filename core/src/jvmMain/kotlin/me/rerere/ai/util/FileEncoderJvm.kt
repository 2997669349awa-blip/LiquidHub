package me.rerere.ai.util

import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.net.URI
import java.util.Base64

/**
 * 桌面(JVM)实现：直接读取文件字节做 base64，不做压缩/EXIF 纠正（无 Android Bitmap）。
 */
actual fun UIMessagePart.Image.encodeBase64(withPrefix: Boolean): Result<EncodedImage> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val path = URI(this.url).path
                ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(path)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val mimeType = file.guessMimeType()
            val encoded = Base64.getEncoder().encodeToString(file.readBytes())
            EncodedImage(
                base64 = if (withPrefix) "data:$mimeType;base64,$encoded" else encoded,
                mimeType = mimeType
            )
        }

        this.url.startsWith("data:") -> {
            val mimeType = url.substringAfter("data:").substringBefore(";")
            EncodedImage(base64 = url, mimeType = mimeType)
        }

        this.url.startsWith("http") -> EncodedImage(base64 = url, mimeType = "image/png")

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

actual fun UIMessagePart.Video.encodeBase64(withPrefix: Boolean): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val path = URI(this.url).path
                ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(path)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = Base64.getEncoder().encodeToString(file.readBytes())
            if (withPrefix) "data:video/mp4;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

actual fun UIMessagePart.Audio.encodeBase64(withPrefix: Boolean): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val path = URI(this.url).path
                ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(path)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = Base64.getEncoder().encodeToString(file.readBytes())
            if (withPrefix) "data:audio/mp3;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

private fun File.guessMimeType(): String = when (extension.lowercase()) {
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "heic", "heif" -> "image/heic"
    "avif" -> "image/avif"
    "jpg", "jpeg" -> "image/jpeg"
    else -> "image/jpeg"
}
