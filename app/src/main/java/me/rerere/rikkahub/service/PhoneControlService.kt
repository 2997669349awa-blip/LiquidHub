// Modified by AI Hello World on 2026-10-03.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 手机控制悬浮球：右上角显示，实时展示 AI 的调用，单击关闭手机控制。

package me.rerere.rikkahub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.phone.PhoneControlManager

class PhoneControlService : Service() {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.phone.START"
        const val ACTION_STOP = "me.rerere.rikkahub.phone.STOP"
        private const val CHANNEL_ID = "phone_control"
        private const val NOTIFICATION_ID = 0x9C12

        fun start(context: Context) {
            val intent = Intent(context, PhoneControlService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            PhoneControlManager.stopSession()
            runCatching { context.stopService(Intent(context, PhoneControlService::class.java)) }
            bringAppToFront(context)
        }

        /** 手机控制结束时自动把 App 拉回前台，方便用户查看结果。 */
        fun bringAppToFront(context: Context) {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ?: return
            runCatching { context.startActivity(intent) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var ballView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                PhoneControlManager.stopSession()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                if (!PhoneControlManager.active.value) PhoneControlManager.startSession()
                showBall()
            }
        }
        return START_STICKY
    }

    private fun showBall() {
        if (ballView != null) return
        val ctx = this
        val view = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#CC2D6CDF"))
            }
            text = renderText()
            setOnClickListener {
                PhoneControlManager.stopSession()
                bringAppToFront(this@PhoneControlService)
                stopSelf()
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(160)
        }
        val wm = windowManager ?: return
        runCatching { wm.addView(view, params) }
            .onSuccess {
                ballView = view
                observeStatus()
            }
    }

    private fun observeStatus() {
        scope.launch {
            PhoneControlManager.status.collect {
                ballView?.text = renderText()
            }
        }
        scope.launch {
            PhoneControlManager.liveText.collect {
                ballView?.text = renderText()
            }
        }
    }

    private fun renderText(): String {
        val action = PhoneControlManager.status.value
        val live = PhoneControlManager.liveText.value.trim()
        return if (live.isNotBlank()) {
            "手机控制 · $action\n${live.takeLast(200)}"
        } else {
            "手机控制\n$action"
        }
    }

    override fun onDestroy() {
        scope.cancel()
        ballView?.let { view -> runCatching { windowManager?.removeView(view) } }
        ballView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "手机控制",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LiquidHub 手机控制")
            .setContentText("AI 正在操作手机，点击悬浮球可停止")
            .setSmallIcon(R.drawable.ic_launcher_liquidhub_foreground)
            .setOngoing(true)
            .build()
    }
}
