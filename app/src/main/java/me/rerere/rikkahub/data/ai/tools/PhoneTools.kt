// Modified by AI Hello World on 2026-10-03.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 手机控制 AI 工具：通过无障碍 + Shizuku 操作本机界面。

package me.rerere.rikkahub.data.ai.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.phone.PhoneControlManager
import me.rerere.rikkahub.data.phone.ShizukuShell
import me.rerere.rikkahub.service.PhoneAccessibilityService
import me.rerere.rikkahub.service.PhoneControlService
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private fun noParams(): InputSchema = InputSchema.Obj(properties = buildJsonObject { }, required = emptyList())

private fun textResult(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): UIMessagePart =
    UIMessagePart.Text(buildJsonObject(build).toString())

private fun accessibility(): PhoneAccessibilityService =
    PhoneAccessibilityService.instance
        ?: error("无障碍服务未开启。请在系统设置 > 无障碍 中启用「LiquidHub 手机控制」。")

private fun openSettings(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun openAccessibilitySettings(context: Context) =
    openSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

private fun openOverlaySettings(context: Context) = openSettings(
    context,
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
)

private suspend fun runShell(command: String): String {
    val context = PhoneControlManager.context()
    ShizukuShell.ensureBound(context)
    return withContext(Dispatchers.IO) { ShizukuShell.exec(command) }
        ?: error("Shizuku 未授权或用户服务未就绪。请在 Shizuku 中授予 LiquidHub 权限后重试。")
}

private fun screenWidth(context: Context): Int = context.resources.displayMetrics.widthPixels
private fun screenHeight(context: Context): Int = context.resources.displayMetrics.heightPixels

fun createPhoneTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "phone_status",
        description = "查询手机控制权限状态（Shizuku / 无障碍 / 悬浮窗）。操作手机前先调用它确认权限。",
        parameters = { noParams() },
        execute = {
            listOf(
                textResult {
                    put("shizuku_binder", PhoneControlManager.isShizukuBinderAlive)
                    put("shizuku_permission", PhoneControlManager.hasShizukuPermission)
                    put("accessibility", PhoneControlManager.isAccessibilityEnabled)
                    put("overlay", PhoneControlManager.hasOverlayPermission())
                    put("active", PhoneControlManager.active.value)
                }
            )
        }
    ),
    Tool(
        name = "phone_start",
        description = "开始手机控制会话：先返回桌面，再在右上角显示悬浮球，悬浮球会实时显示当前动作，单击即可停止。" +
            "若缺少无障碍/悬浮窗权限，会自动打开对应系统设置页，请用户开启后再次调用。",
        parameters = { noParams() },
        execute = {
            val ctx = PhoneControlManager.context()
            val problems = mutableListOf<String>()
            if (!PhoneControlManager.hasOverlayPermission()) {
                problems += "悬浮窗权限"
                openOverlaySettings(ctx)
            }
            if (!PhoneControlManager.isAccessibilityEnabled) {
                problems += "无障碍服务"
                openAccessibilitySettings(ctx)
            }
            if (PhoneControlManager.isShizukuBinderAlive && !PhoneControlManager.hasShizukuPermission) {
                ShizukuShell.requestPermission()
                problems += "Shizuku 授权"
            }
            ShizukuShell.ensureBound(ctx)
            var started = false
            if (PhoneControlManager.isAccessibilityEnabled && PhoneControlManager.hasOverlayPermission()) {
                // 先在前台启动悬浮球，再回桌面：避免 Android 12+ 在后台启动前台服务被拒。
                PhoneControlManager.startSession()
                runCatching { PhoneControlService.start(ctx) }
                delay(400)
                accessibility().global(AccessibilityService.GLOBAL_ACTION_HOME)
                started = true
            }
            listOf(
                textResult {
                    put("ok", started)
                    put("active", PhoneControlManager.active.value)
                    if (problems.isNotEmpty()) {
                        put("需要授权", problems.joinToString("、"))
                        put("hint", "已在手机上打开相应设置页，请开启后再次调用 phone_start。")
                    }
                }
            )
        }
    ),
    Tool(
        name = "phone_stop",
        description = "停止手机控制会话并移除悬浮球。",
        parameters = { noParams() },
        execute = {
            val ctx = PhoneControlManager.context()
            PhoneControlManager.stopSession()
            PhoneControlService.stop(ctx)
            listOf(textResult { put("ok", true) })
        }
    ),
    Tool(
        name = "phone_home",
        description = "返回手机桌面。",
        parameters = { noParams() },
        execute = {
            PhoneControlManager.setStatus("返回桌面")
            val ok = accessibility().global(AccessibilityService.GLOBAL_ACTION_HOME)
            listOf(textResult { put("ok", ok) })
        }
    ),
    Tool(
        name = "phone_back",
        description = "执行返回操作。",
        parameters = { noParams() },
        execute = {
            PhoneControlManager.setStatus("返回")
            val ok = accessibility().global(AccessibilityService.GLOBAL_ACTION_BACK)
            listOf(textResult { put("ok", ok) })
        }
    ),
    Tool(
        name = "phone_recents",
        description = "打开最近任务。",
        parameters = { noParams() },
        execute = {
            PhoneControlManager.setStatus("最近任务")
            val ok = accessibility().global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            listOf(textResult { put("ok", ok) })
        }
    ),
    Tool(
        name = "phone_open_app",
        description = "打开应用。优先传 `package`（包名，如 com.ss.android.ugc.aweme）；只传 `app`（应用名）时会尝试模糊匹配。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("package", buildJsonObject {
                        put("type", "string")
                        put("description", "包名，例如 com.ss.android.ugc.aweme")
                    })
                    put("app", buildJsonObject {
                        put("type", "string")
                        put("description", "应用名称，例如 抖音")
                    })
                },
                required = emptyList()
            )
        },
        execute = {
            val pkgArg = it.str("package")
            val nameArg = it.str("app")
            val ctx = PhoneControlManager.context()
            val pm = ctx.packageManager
            val resolved = pkgArg.ifBlank { findPackageByName(ctx, nameArg) }
            if (resolved.isBlank()) error("未找到应用：$nameArg。请提供准确的包名 package。")
            PhoneControlManager.setStatus("打开应用 $resolved")
            val launch = pm.getLaunchIntentForPackage(resolved)
            val ok = if (launch != null) {
                runCatching {
                    ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }.getOrDefault(false)
            } else {
                // 兜底：Shizuku 以 shell 身份启动
                runCatching {
                    runShell("monkey -p $resolved -c android.intent.category.LAUNCHER 1")
                    true
                }.getOrDefault(false)
            }
            listOf(textResult { put("ok", ok); put("package", resolved) })
        }
    ),
    Tool(
        name = "phone_tap",
        description = "点击屏幕坐标 (x, y)。坐标基于当前屏幕像素，可用 phone_read_screen 获取元素坐标。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("x", buildJsonObject { put("type", "number"); put("description", "横坐标像素") })
                    put("y", buildJsonObject { put("type", "number"); put("description", "纵坐标像素") })
                },
                required = listOf("x", "y")
            )
        },
        execute = {
            val x = it.str("x").toFloatOrNull() ?: error("x is required")
            val y = it.str("y").toFloatOrNull() ?: error("y is required")
            PhoneControlManager.setStatus("点击 ($x,$y)")
            val ok = accessibility().tap(x, y)
            listOf(textResult { put("ok", ok) })
        }
    ),
    Tool(
        name = "phone_swipe",
        description = "从 (x1,y1) 滑动到 (x2,y2)，durationMs 为滑动时长（毫秒，默认 300）。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("x1", buildJsonObject { put("type", "number") })
                    put("y1", buildJsonObject { put("type", "number") })
                    put("x2", buildJsonObject { put("type", "number") })
                    put("y2", buildJsonObject { put("type", "number") })
                    put("duration", buildJsonObject { put("type", "number"); put("description", "毫秒，默认 300") })
                },
                required = listOf("x1", "y1", "x2", "y2")
            )
        },
        execute = {
            val x1 = it.str("x1").toFloatOrNull() ?: error("x1 required")
            val y1 = it.str("y1").toFloatOrNull() ?: error("y1 required")
            val x2 = it.str("x2").toFloatOrNull() ?: error("x2 required")
            val y2 = it.str("y2").toFloatOrNull() ?: error("y2 required")
            val duration = it.str("duration").toLongOrNull() ?: 300L
            PhoneControlManager.setStatus("滑动")
            val ok = accessibility().swipe(x1, y1, x2, y2, duration)
            listOf(textResult { put("ok", ok) })
        }
    ),
    Tool(
        name = "phone_scroll",
        description = "在当前界面滚动。direction 为 up（看下一条，即手指上滑）/ down / left / right，distance 为比例（默认 0.4）。" +
            "刷短视频时用 up。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("direction", buildJsonObject { put("type", "string"); put("description", "up/down/left/right，默认 up") })
                    put("distance", buildJsonObject { put("type", "number"); put("description", "滑动比例 0.1~0.9，默认 0.4") })
                },
                required = emptyList()
            )
        },
        execute = {
            val direction = it.str("direction").ifBlank { "up" }.lowercase()
            val distance = (it.str("distance").toFloatOrNull() ?: 0.4f).coerceIn(0.1f, 0.9f)
            val ctx = PhoneControlManager.context()
            val w = screenWidth(ctx).toFloat()
            val h = screenHeight(ctx).toFloat()
            PhoneControlManager.setStatus("滚动 $direction")
            val ok = when (direction) {
                "down" -> accessibility().swipe(w / 2, h * (0.35f), w / 2, h * (0.35f + distance), 300)
                "left" -> accessibility().swipe(w * (0.85f), h / 2, w * (0.85f - distance), h / 2, 300)
                "right" -> accessibility().swipe(w * (0.15f), h / 2, w * (0.15f + distance), h / 2, 300)
                else -> accessibility().swipe(w / 2, h * (0.72f), w / 2, h * (0.72f - distance), 300)
            }
            listOf(textResult { put("ok", ok) })
        }
    ),
    Tool(
        name = "phone_text",
        description = "向当前输入框输入文本。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", buildJsonObject { put("type", "string") })
                },
                required = listOf("text")
            )
        },
        execute = {
            val text = it.str("text")
            PhoneControlManager.setStatus("输入文本")
            val ok = PhoneAccessibilityService.instance?.setTextOnFocus(text) ?: false
            if (!ok) {
                runCatching { runShell("input text ${text.replace(" ", "%s")}") }
            }
            listOf(textResult { put("ok", ok) })
        }
    ),
    Tool(
        name = "phone_key",
        description = "发送按键：back/home/recents/enter/delete/volume_up/volume_down/power，或直接传数字 keycode。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("key", buildJsonObject { put("type", "string") })
                },
                required = listOf("key")
            )
        },
        execute = {
            val key = it.str("key").lowercase()
            PhoneControlManager.setStatus("按键 $key")
            val ok = when (key) {
                "back" -> accessibility().global(AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> accessibility().global(AccessibilityService.GLOBAL_ACTION_HOME)
                "recents", "app_switch" -> accessibility().global(AccessibilityService.GLOBAL_ACTION_RECENTS)
                "notifications" -> accessibility().global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                else -> {
                    val code = when (key) {
                        "enter" -> 66
                        "delete", "backspace" -> 67
                        "volume_up" -> 24
                        "volume_down" -> 25
                        "power" -> 26
                        "menu" -> 82
                        else -> key.toIntOrNull() ?: error("未知按键：$key")
                    }
                    runCatching { runShell("input keyevent $code") }.isSuccess
                }
            }
            listOf(textResult { put("ok", ok) })
        }
    ),
    Tool(
        name = "phone_click_text",
        description = "在当前界面查找包含指定文字的可点击控件并点击。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", buildJsonObject { put("type", "string") })
                },
                required = listOf("text")
            )
        },
        execute = {
            val text = it.str("text")
            PhoneControlManager.setStatus("点击「$text」")
            val ok = accessibility().clickByText(text)
            listOf(textResult { put("ok", ok) })
        }
    ),
    Tool(
        name = "phone_read_screen",
        description = "读取当前屏幕上的可见文字与坐标，用于判断界面并决定下一步点击位置。",
        parameters = { noParams() },
        execute = {
            PhoneControlManager.setStatus("读取屏幕")
            val text = accessibility().dumpScreen()
            listOf(UIMessagePart.Text(text.ifBlank { "(未读取到文字)" }))
        }
    ),
    Tool(
        name = "phone_screenshot",
        description = "截取当前屏幕。需要 Android 11+ 且无障碍已开启。",
        parameters = { noParams() },
        execute = {
            PhoneControlManager.setStatus("截图")
            val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                accessibility().takeScreenshotBitmap()
            } else {
                null
            }
            require(bitmap != null) { "截图失败：需要 Android 11+ 且无障碍已开启" }
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            val filesManager = getKoin().get<FilesManager>()
            val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
            listOf(
                UIMessagePart.Image(url = uris.first().toString()),
                textResult { put("ok", true); put("size", bytes.size) }
            )
        }
    ),
    Tool(
        name = "phone_wait",
        description = "等待指定毫秒，用于等界面加载。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("ms", buildJsonObject { put("type", "number"); put("description", "毫秒，默认 1000，最大 60000") })
                },
                required = emptyList()
            )
        },
        execute = {
            val ms = (it.str("ms").toLongOrNull() ?: 1000L).coerceIn(0L, 60_000L)
            PhoneControlManager.setStatus("等待 ${ms}ms")
            delay(ms)
            listOf(textResult { put("ok", true) })
        }
    ),
    Tool(
        name = "phone_auto_scroll",
        description = "自动上滑刷内容（如短视频），每滑一次间隔 intervalMs，共 times 次；" +
            "期间悬浮球会显示进度，用户单击悬浮球可立即停止。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("times", buildJsonObject { put("type", "number"); put("description", "滑动次数，默认 10，最大 500") })
                    put("interval", buildJsonObject { put("type", "number"); put("description", "间隔毫秒，默认 3000") })
                },
                required = emptyList()
            )
        },
        execute = {
            val times = (it.str("times").toIntOrNull() ?: 10).coerceIn(1, 500)
            val interval = (it.str("interval").toLongOrNull() ?: 3000L).coerceIn(300L, 60_000L)
            val ctx = PhoneControlManager.context()
            val w = screenWidth(ctx).toFloat()
            val h = screenHeight(ctx).toFloat()
            var done = 0
            for (i in 1..times) {
                if (PhoneControlManager.stopRequested) break
                PhoneControlManager.setStatus("自动上滑 $i/$times")
                accessibility().swipe(w / 2, h * 0.72f, w / 2, h * 0.32f, 300)
                done = i
                delay(interval)
            }
            listOf(
                textResult {
                    put("ok", true)
                    put("done", done)
                    put("stopped", PhoneControlManager.stopRequested)
                }
            )
        }
    ),
)

private fun JsonElement.str(key: String): String =
    (this as? JsonObject)
        ?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()

private fun findPackageByName(context: Context, name: String): String {
    if (name.isBlank()) return ""
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val activities = pm.queryIntentActivities(intent, 0)
    return activities.firstOrNull {
        runCatching { pm.getApplicationLabel(it.activityInfo.applicationInfo).toString() }
            .getOrDefault("").contains(name, ignoreCase = true)
    }?.activityInfo?.packageName.orEmpty()
}
