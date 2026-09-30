// Modified by AI Hello World on 2026-09-30.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
//
// 工作区桌面的 VNC 输出/输入不再自研，直接复用 tiny_container 的做法：
// 用 AVNC 库（com.github.tiny-computer:avnc）打开 VNC 会话，Unix socket 优先。

package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.ui.vnc.createVncIntent
import com.gaurav.avnc.vnc.VncUri

/**
 * 用 AVNC 打开工作区桌面。
 *
 * @param unixSocketPath 宿主机上 VNC 的 Unix socket 绝对路径（容器内 /workspace/.vnc）。
 *        非空时走 Unix socket；为空则回退到 127.0.0.1:[port] 的 TCP。
 */
object AvncLauncher {
    fun launch(
        context: Context,
        unixSocketPath: String?,
        host: String = "127.0.0.1",
        port: Int = 5900,
    ) {
        val uri = "vnc://$host:$port".toUri().buildUpon().apply {
            if (!unixSocketPath.isNullOrBlank()) {
                appendQueryParameter("UnixSocket", unixSocketPath)
            }
        }.build()
        val profile = VncUri(uri.toString()).applyToProfile(ServerProfile())
        val intent: Intent = createVncIntent(context, profile)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
