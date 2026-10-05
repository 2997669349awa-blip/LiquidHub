// Modified by AI Hello World on 2026-10-05.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 执行“网页端发起、手机端已确认”的手机控制动作。

package me.rerere.rikkahub.data.phone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import me.rerere.rikkahub.service.PhoneAccessibilityService
import java.io.ByteArrayOutputStream

object WebPhoneExecutor {

    suspend fun execute(context: Context, action: String, args: Map<String, String>): String {
        fun service(): PhoneAccessibilityService =
            PhoneAccessibilityService.instance ?: error("无障碍服务未开启，请先在手机控制里授权")

        return when (action) {
            "home" -> ok(service().global(AccessibilityService.GLOBAL_ACTION_HOME))
            "back" -> ok(service().global(AccessibilityService.GLOBAL_ACTION_BACK))
            "recents" -> ok(service().global(AccessibilityService.GLOBAL_ACTION_RECENTS))
            "notifications" -> ok(service().global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))

            "tap" -> ok(
                service().tap(
                    args["x"]?.toFloatOrNull() ?: error("缺少 x"),
                    args["y"]?.toFloatOrNull() ?: error("缺少 y"),
                )
            )

            "swipe" -> ok(
                service().swipe(
                    args["x1"]?.toFloatOrNull() ?: error("缺少 x1"),
                    args["y1"]?.toFloatOrNull() ?: error("缺少 y1"),
                    args["x2"]?.toFloatOrNull() ?: error("缺少 x2"),
                    args["y2"]?.toFloatOrNull() ?: error("缺少 y2"),
                    args["duration"]?.toLongOrNull() ?: 300L,
                )
            )

            "scroll" -> {
                val direction = args["direction"]?.lowercase() ?: "up"
                val distance = (args["distance"]?.toFloatOrNull() ?: 0.4f).coerceIn(0.1f, 0.9f)
                val w = context.resources.displayMetrics.widthPixels.toFloat()
                val h = context.resources.displayMetrics.heightPixels.toFloat()
                val ok = when (direction) {
                    "down" -> service().swipe(w / 2f, h * (0.5f - distance / 2f), w / 2f, h * (0.5f + distance / 2f), 300L)
                    "left" -> service().swipe(w * (0.5f + distance / 2f), h / 2f, w * (0.5f - distance / 2f), h / 2f, 300L)
                    "right" -> service().swipe(w * (0.5f - distance / 2f), h / 2f, w * (0.5f + distance / 2f), h / 2f, 300L)
                    else -> service().swipe(w / 2f, h * (0.5f + distance / 2f), w / 2f, h * (0.5f - distance / 2f), 300L)
                }
                ok(ok)
            }

            "text" -> ok(service().setText(args["text"].orEmpty()))
            "click_text" -> ok(service().clickByText(args["text"].orEmpty()))
            "read_screen" -> service().dumpScreen().take(4000)

            "key" -> {
                val key = args["key"]?.lowercase().orEmpty()
                val code = when (key) {
                    "back" -> 4
                    "home" -> 3
                    "enter" -> 66
                    "delete" -> 67
                    "volume_up" -> 24
                    "volume_down" -> 25
                    "power" -> 26
                    else -> error("不支持的按键: $key")
                }
                ShizukuShell.exec("input keyevent $code")?.let { ok(it.isNotBlank()) } ?: ok(false)
            }

            "open_app" -> {
                val pkg = args["package"].orEmpty().ifBlank { args["app"].orEmpty() }
                if (pkg.isBlank()) error("缺少 package")
                val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: error("找不到应用: $pkg")
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                ok(true)
            }

            "screenshot" -> {
                val bitmap = service().takeScreenshotBitmap() ?: error("截图失败")
                toBase64(bitmap)
            }

            else -> error("未知操作: $action")
        }
    }

    private fun ok(success: Boolean): String = if (success) "成功" else "失败"

    private fun toBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
