// Modified by AI Hello World on 2026-09-25.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// Exposes the workspace remote desktop to the model: screenshot, click, type, keys, browser.

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.workspace.WorkspaceDesktopManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

internal const val DESKTOP_SHOT_PATH = "/tmp/liquidhub-shot.png"
private const val DESKTOP_TIMEOUT_MS = 120_000L
private const val SCREENSHOT_MAX_BYTES = 16L * 1024 * 1024

val DesktopToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "desktop_start" to false,
    "desktop_stop" to false,
    "desktop_screenshot" to false,
    "desktop_click" to true,
    "desktop_type_text" to true,
    "desktop_key" to true,
    "desktop_browser" to true,
    "desktop_wait_user" to false,
    "desktop_move" to false,
    "desktop_scroll" to true,
    "desktop_drag" to true,
)

internal suspend fun createDesktopTools(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    desktopManager: WorkspaceDesktopManager,
): List<Tool> {
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)
    return listOf(
        createDesktopStartTool(workspaceId, workspaceRepository, desktopManager, ::needsApproval),
        createDesktopStopTool(workspaceId, desktopManager, ::needsApproval),
        createDesktopScreenshotTool(workspaceId, workspaceRepository, ::needsApproval),
        createDesktopClickTool(workspaceId, workspaceRepository, ::needsApproval),
        createDesktopTypeTool(workspaceId, workspaceRepository, ::needsApproval),
        createDesktopKeyTool(workspaceId, workspaceRepository, ::needsApproval),
        createDesktopBrowserTool(workspaceId, workspaceRepository, desktopManager, ::needsApproval),
        createDesktopWaitUserTool(::needsApproval),
        createDesktopMoveTool(workspaceId, workspaceRepository, ::needsApproval),
        createDesktopScrollTool(workspaceId, workspaceRepository, ::needsApproval),
        createDesktopDragTool(workspaceId, workspaceRepository, ::needsApproval),
    )
}

private fun createDesktopStartTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    desktopManager: WorkspaceDesktopManager,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "desktop_start",
    description = "Start the workspace remote desktop (X server + window manager + VNC + browser). " +
        "The desktop is embedded and fully AI-driven: if it is not installed yet it is installed " +
        "automatically (this can take a few minutes), so the user never has to install anything by hand. " +
        "After starting, wait a few seconds before calling desktop_screenshot.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    needsApproval = { needsApproval("desktop_start") },
    execute = {
        val installed = workspaceRepository.executeCommand(
            id = workspaceId,
            command = "command -v Xvfb >/dev/null 2>&1 && echo INSTALLED || echo MISSING",
            timeoutMillis = 30_000,
        ).stdout.contains("INSTALLED")
        if (!installed) {
            // 内嵌桌面：首次由 AI 自动安装，用户无需手动操作
            desktopManager.ensureScript(workspaceId)
            val install = workspaceRepository.executeCommand(
                id = workspaceId,
                command = "sh /workspace/${WorkspaceDesktopManager.SCRIPT_PATH} install",
                timeoutMillis = 30 * 60_000L,
            )
            if (install.exitCode != 0) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", false)
                            put("error", "Desktop auto-install failed (exit ${install.exitCode}).")
                            put("log", (install.stdout + install.stderr).takeLast(2000))
                        }.toString()
                    )
                )
            }
        }
        desktopManager.runAction(workspaceId, "start")
        desktopManager.runAction(workspaceId, "browser")
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("ok", true)
                    put("message", "Desktop is starting. Wait ~5 seconds, then call desktop_screenshot.")
                }.toString()
            )
        )
    },
)

private fun createDesktopStopTool(
    workspaceId: String,
    desktopManager: WorkspaceDesktopManager,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "desktop_stop",
    description = "Stop the workspace remote desktop and its browser.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    needsApproval = { needsApproval("desktop_stop") },
    execute = {
        desktopManager.runAction(workspaceId, "stop")
        listOf(UIMessagePart.Text("{\"ok\":true}"))
    },
)

private fun createDesktopScreenshotTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "desktop_screenshot",
    description = "Take a screenshot of the workspace remote desktop (1280x720). " +
        "Returns the image so you can see the screen and decide where to click or type.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    needsApproval = { needsApproval("desktop_screenshot") },
    execute = {
        val command = """
            mkdir -p /tmp/.liquidhub-desktop
            rm -f $DESKTOP_SHOT_PATH
            if command -v scrot >/dev/null 2>&1; then
              DISPLAY=:1 scrot -o $DESKTOP_SHOT_PATH 2>&1
            else
              DISPLAY=:1 import -window root $DESKTOP_SHOT_PATH 2>&1
            fi
            if [ -s $DESKTOP_SHOT_PATH ]; then echo SHOT_OK; else echo SHOT_FAILED; fi
        """.trimIndent()
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = command,
            timeoutMillis = DESKTOP_TIMEOUT_MS,
        )
        val size = runCatching { workspaceRepository.rootfsFileSize(workspaceId, DESKTOP_SHOT_PATH) }
            .getOrDefault(0L)
        if (result.exitCode != 0 || size <= 0L || !result.stdout.contains("SHOT_OK")) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", false)
                        put("exitCode", result.exitCode)
                        put("stdout", result.stdout)
                        put("stderr", result.stderr)
                        put("hint", "Is the desktop running? Call desktop_start first.")
                    }.toString()
                )
            )
        }
        listOf(
            UIMessagePart.Image(url = persistScreenshot(workspaceId, workspaceRepository)),
            UIMessagePart.Text(
                buildJsonObject {
                    put("ok", true)
                    put("size", size)
                    put("hint", "Coordinates are in a 1280x720 space. Use desktop_click(x, y).")
                }.toString()
            ),
        )
    },
)

private suspend fun persistScreenshot(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
): String {
    val size = workspaceRepository.rootfsFileSize(workspaceId, DESKTOP_SHOT_PATH)
    require(size in 1..SCREENSHOT_MAX_BYTES) { "Invalid screenshot size: $size" }
    val buffer = ByteArrayOutputStream(size.toInt())
    workspaceRepository.exportRootfsFile(workspaceId, DESKTOP_SHOT_PATH, buffer)
    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(buffer.toByteArray()))
    return uris.first().toString()
}

private fun createDesktopClickTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "desktop_click",
    description = "Move the mouse to (x, y) on the remote desktop and click. " +
        "Coordinates match the 1280x720 screenshot.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x", buildJsonObject { put("type", "integer"); put("description", "X coordinate") })
                put("y", buildJsonObject { put("type", "integer"); put("description", "Y coordinate") })
                put(
                    "button",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "left (default), right or middle")
                    }
                )
            },
            required = listOf("x", "y"),
        )
    },
    needsApproval = { needsApproval("desktop_click") },
    execute = {
        val params = it.jsonObject
        val x = params.intValue("x") ?: error("x is required")
        val y = params.intValue("y") ?: error("y is required")
        require(x in 0..10_000 && y in 0..10_000) { "Coordinates out of range" }
        val button = when (params.stringValue("button")?.lowercase()) {
            null, "left" -> "1"
            "middle" -> "2"
            "right" -> "3"
            else -> error("Unsupported button")
        }
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = "DISPLAY=:1 xdotool mousemove -- $x $y click $button && echo CLICK_OK",
            timeoutMillis = 30_000,
        )
        listOf(UIMessagePart.Text(commandResultJson(result)))
    },
)

private fun createDesktopTypeTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "desktop_type_text",
    description = "Type text into the focused field of the remote desktop. " +
        "Set press_enter to true to submit the text (e.g. login forms).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject { put("type", "string"); put("description", "Text to type") })
                put(
                    "press_enter",
                    buildJsonObject {
                        put("type", "boolean")
                        put("description", "Press Enter after typing. Defaults to false.")
                    }
                )
            },
            required = listOf("text"),
        )
    },
    needsApproval = { needsApproval("desktop_type_text") },
    execute = {
        val params = it.jsonObject
        val text = params.stringValue("text") ?: error("text is required")
        val pressEnter = params["press_enter"]?.jsonPrimitive?.contentOrNull == "true"
        val tail = if (pressEnter) "; DISPLAY=:1 xdotool key --clearmodifiers Return" else ""
        val command = "DISPLAY=:1 xdotool type --clearmodifiers --delay 30 --file -$tail && echo TYPE_OK"
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = command,
            timeoutMillis = DESKTOP_TIMEOUT_MS,
            stdin = text.toByteArray(Charsets.UTF_8),
        )
        listOf(UIMessagePart.Text(commandResultJson(result)))
    },
)

private fun createDesktopKeyTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "desktop_key",
    description = "Press a key or key combination on the remote desktop, for example " +
        "\"Return\", \"Escape\", \"ctrl+l\", \"ctrl+t\", \"Page_Down\", \"alt+F4\".",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("key", buildJsonObject { put("type", "string"); put("description", "Keysym or chord") })
            },
            required = listOf("key"),
        )
    },
    needsApproval = { needsApproval("desktop_key") },
    execute = {
        val key = it.jsonObject.stringValue("key") ?: error("key is required")
        require(KEY_PATTERN.matches(key)) { "Invalid key: $key" }
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = "DISPLAY=:1 xdotool key --clearmodifiers $key && echo KEY_OK",
            timeoutMillis = 30_000,
        )
        listOf(UIMessagePart.Text(commandResultJson(result)))
    },
)

private fun createDesktopBrowserTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    desktopManager: WorkspaceDesktopManager,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "desktop_browser",
    description = "Open or navigate Chromium to a URL on the remote desktop. " +
        "Use this to reach a login page, then ask the user to take over and sign in.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject { put("type", "string"); put("description", "Absolute http(s) URL") })
            },
            required = listOf("url"),
        )
    },
    needsApproval = { needsApproval("desktop_browser") },
    execute = {
        val url = it.jsonObject.stringValue("url") ?: error("url is required")
        require(url.startsWith("http://") || url.startsWith("https://")) { "Only http(s) URLs are allowed" }
        desktopManager.runAction(workspaceId, "browser ${url.shellSingleQuote()}")
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("ok", true)
                    put("message", "Chromium is opening $url. Wait a few seconds and screenshot.")
                }.toString()
            )
        )
    },
)

private fun createDesktopWaitUserTool(
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "desktop_wait_user",
    description = "Call this when the user must take over the remote desktop by hand (login, 2FA, " +
        "captcha, payment...). In your reply clearly tell the user what to do, then STOP and wait " +
        "for their next message. Do not keep calling desktop tools until the user says they are done.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put(
                    "reason",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Why the user needs to take over")
                    }
                )
            },
            required = listOf("reason"),
        )
    },
    needsApproval = { needsApproval("desktop_wait_user") },
    execute = {
        val reason = it.jsonObject.stringValue("reason") ?: "需要用户接管桌面"
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("ok", true)
                    put("message", "已请求用户接管桌面（$reason）。现在停止操作，等待用户回复后再继续。")
                }.toString()
            )
        )
    },
)

private fun createDesktopMoveTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "desktop_move",
    description = "Move the mouse pointer to (x, y) on the remote desktop (no click). Coordinates match the 1280x720 screenshot.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x", buildJsonObject { put("type", "integer") })
                put("y", buildJsonObject { put("type", "integer") })
            },
            required = listOf("x", "y"),
        )
    },
    needsApproval = { needsApproval("desktop_move") },
    execute = {
        val x = it.jsonObject.intValue("x") ?: error("x is required")
        val y = it.jsonObject.intValue("y") ?: error("y is required")
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = "DISPLAY=:1 xdotool mousemove -- $x $y && echo MOVE_OK",
            timeoutMillis = 30_000,
        )
        listOf(UIMessagePart.Text(commandResultJson(result)))
    },
)

private fun createDesktopScrollTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "desktop_scroll",
    description = "Scroll the remote desktop. direction is up or down; amount is wheel clicks (default 5).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("direction", buildJsonObject { put("type", "string"); put("description", "up or down") })
                put("amount", buildJsonObject { put("type", "integer"); put("description", "wheel clicks, default 5") })
            },
            required = listOf("direction"),
        )
    },
    needsApproval = { needsApproval("desktop_scroll") },
    execute = {
        val direction = it.jsonObject.stringValue("direction")?.lowercase() ?: "down"
        val amount = (it.jsonObject.intValue("amount") ?: 5).coerceIn(1, 50)
        val button = if (direction == "up") "4" else "5"
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = "DISPLAY=:1 xdotool click --repeat $amount $button && echo SCROLL_OK",
            timeoutMillis = 30_000,
        )
        listOf(UIMessagePart.Text(commandResultJson(result)))
    },
)

private fun createDesktopDragTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    needsApproval: (String) -> Boolean,
) = Tool(
    name = "desktop_drag",
    description = "Drag on the remote desktop from (x1, y1) to (x2, y2) with the left button.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x1", buildJsonObject { put("type", "integer") })
                put("y1", buildJsonObject { put("type", "integer") })
                put("x2", buildJsonObject { put("type", "integer") })
                put("y2", buildJsonObject { put("type", "integer") })
            },
            required = listOf("x1", "y1", "x2", "y2"),
        )
    },
    needsApproval = { needsApproval("desktop_drag") },
    execute = {
        val p = it.jsonObject
        val x1 = p.intValue("x1") ?: error("x1 is required")
        val y1 = p.intValue("y1") ?: error("y1 is required")
        val x2 = p.intValue("x2") ?: error("x2 is required")
        val y2 = p.intValue("y2") ?: error("y2 is required")
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = "DISPLAY=:1 xdotool mousemove -- $x1 $y1 mousedown 1 mousemove -- $x2 $y2 mouseup 1 && echo DRAG_OK",
            timeoutMillis = 30_000,
        )
        listOf(UIMessagePart.Text(commandResultJson(result)))
    },
)

private val KEY_PATTERN = Regex("^[A-Za-z0-9_+\\- ]{1,64}$")

private fun JsonObject.stringValue(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.intValue(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun commandResultJson(result: me.rerere.workspace.WorkspaceCommandResult): String =
    buildJsonObject {
        put("exitCode", result.exitCode)
        put("stdout", result.stdout)
        put("stderr", result.stderr)
    }.toString()

private fun String.shellSingleQuote(): String = "'" + replace("'", "'\\''") + "'"
