// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 「问这个屏幕」：截屏 + 读取屏幕文字，打开分享对话页让 AI 分析当前屏幕。

package me.rerere.rikkahub.data.phone

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.service.PhoneAccessibilityService
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

object ScreenAskHelper {
    private const val MAX_SCREEN_TEXT = 4_000

    fun askCurrentScreen(context: Context) {
        val appContext = context.applicationContext ?: context
        val appScope = getKoin().get<AppScope>()
        appScope.launch {
            if (PhoneAccessibilityService.instance == null) {
                Toast.makeText(appContext, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                runCatching {
                    appContext.startActivity(
                        Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return@launch
            }
            val service = PhoneAccessibilityService.instance ?: return@launch
            val screenText = runCatching { service.dumpScreen() }
                .getOrDefault("")
                .take(MAX_SCREEN_TEXT)
            val imageUri = runCatching { captureScreenshot(appContext, service) }.getOrNull()

            val prompt = buildString {
                append("请看我当前的手机屏幕，简要说明这是什么界面，并给出下一步建议。")
                if (screenText.isNotBlank()) {
                    append("\n\n[屏幕文字]\n")
                    append(screenText)
                }
            }
            val intent = Intent(appContext, RouteActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "image/png"
                putExtra(Intent.EXTRA_TEXT, prompt)
                imageUri?.let { putExtra(Intent.EXTRA_STREAM, it) }
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            runCatching { appContext.startActivity(intent) }
                .onFailure { Toast.makeText(appContext, "无法打开对话页", Toast.LENGTH_SHORT).show() }
        }
    }

    private suspend fun captureScreenshot(
        context: Context,
        service: PhoneAccessibilityService,
    ): Uri? {
        val bitmap: Bitmap = service.takeScreenshotBitmap() ?: return null
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)
            output.toByteArray()
        }
        val filesManager = getKoin().get<FilesManager>()
        return filesManager.createChatFilesByByteArrays(listOf(bytes)).firstOrNull()
    }
}
