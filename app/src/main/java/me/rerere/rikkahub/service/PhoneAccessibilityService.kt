// Modified by AI Hello World on 2026-10-03.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 手机控制无障碍服务：手势、全局操作、读屏、截图。

package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PhoneAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PhoneAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun global(action: Int): Boolean = runCatching { performGlobalAction(action) }.getOrDefault(false)

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        return runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }.getOrDefault(false)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(60, 6000))
        return runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }.getOrDefault(false)
    }

    fun setTextOnFocus(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrDefault(false)
    }

    /** 一次性把整段文本写入输入框：优先焦点输入框，其次页面里第一个可编辑控件。 */
    fun setText(input: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focused?.takeIf { it.isEditable } ?: findFirstEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input)
        }
        return runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrDefault(false)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findFirstEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNodeByText(root, text) ?: return false
        repeat(3) {
            var node: AccessibilityNodeInfo? = target
            while (node != null) {
                if (node.isClickable) {
                    return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        .getOrDefault(false)
                }
                node = node.parent
            }
            return@repeat
        }
        return false
    }

    private fun findNodeByText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val label = node.text?.toString().orEmpty().ifEmpty { node.contentDescription?.toString().orEmpty() }
        if (label.contains(text, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            findNodeByText(node.getChild(i), text)?.let { return it }
        }
        return null
    }

    /** 列出屏幕上的可见文字与坐标，供 AI 判断界面。 */
    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val builder = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40 || builder.length > 20_000) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty()) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (!bounds.isEmpty) {
                    builder.append("  ".repeat(depth.coerceAtMost(8)))
                    if (text.isNotEmpty()) builder.append(text)
                    if (desc.isNotEmpty()) builder.append(" [").append(desc).append(']')
                    builder.append(" (").append(bounds.centerX()).append(',').append(bounds.centerY())
                        .append(")\n")
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        runCatching { walk(root, 0) }
        return builder.toString()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun takeScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = runCatching {
                        val buffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val bmp = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                        val copy = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer?.close()
                        copy
                    }.getOrNull()
                    if (cont.isActive) cont.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            runCatching {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }
}
