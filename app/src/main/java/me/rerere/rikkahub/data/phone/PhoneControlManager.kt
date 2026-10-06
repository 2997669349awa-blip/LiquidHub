// Modified by AI Hello World on 2026-10-03.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 手机控制会话状态：悬浮球实时显示 AI 的调用，单击停止。

package me.rerere.rikkahub.data.phone

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.service.PhoneAccessibilityService

/** 网页端发起、需要手机端手动确认的手机控制请求。 */
data class WebPhoneRequest(
    val id: String,
    val action: String,
    val label: String,
    val args: Map<String, String> = emptyMap(),
)

object PhoneControlManager {
    @Volatile
    private var appContext: Context? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _status = MutableStateFlow("待命")
    val status: StateFlow<String> = _status.asStateFlow()

    // 悬浮球上实时显示的 AI 思考/回复文本
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()

    // 需要手机端手动确认的网页请求
    private val _pendingWebRequest = MutableStateFlow<WebPhoneRequest?>(null)
    val pendingWebRequest: StateFlow<WebPhoneRequest?> = _pendingWebRequest.asStateFlow()

    private var pendingDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    var stopRequested: Boolean = false
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun context(): Context = appContext ?: error("PhoneControlManager 尚未初始化")

    val isShizukuBinderAlive: Boolean get() = ShizukuShell.isBinderAlive()
    val hasShizukuPermission: Boolean get() = ShizukuShell.hasPermission()
    val isAccessibilityEnabled: Boolean get() = PhoneAccessibilityService.instance != null

    fun hasOverlayPermission(): Boolean {
        val ctx = appContext ?: return false
        return runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)
    }

    fun startSession() {
        stopRequested = false
        _liveText.value = ""
        _active.value = true
        setStatus("手机控制已开启")
    }

    fun stopSession() {
        stopRequested = true
        _active.value = false
        _liveText.value = ""
        setStatus("手机控制已停止")
        respondWebRequest(false)
    }

    /** 彻底终止手机控制：结束会话并停掉前台服务与悬浮窗。 */
    fun stopAll() {
        stopSession()
        appContext?.let { ctx ->
            runCatching { me.rerere.rikkahub.service.PhoneControlService.stop(ctx) }
        }
    }

    /** 等待手机端对网页请求做出确认；超时视为拒绝。 */
    suspend fun awaitWebApproval(request: WebPhoneRequest, timeoutMs: Long = 30_000L): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingDeferred = deferred
        _pendingWebRequest.value = request
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            _pendingWebRequest.value = null
            pendingDeferred = null
        }
    }

    /** 手机端点击允许/拒绝。 */
    fun respondWebRequest(approved: Boolean) {
        pendingDeferred?.complete(approved)
    }

    fun setStatus(text: String) {
        _status.value = text
    }

    fun setLiveText(text: String) {
        _liveText.value = text
    }
}
