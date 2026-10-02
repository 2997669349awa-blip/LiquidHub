// Modified by AI Hello World on 2026-10-03.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 全局音乐会话：把网易云登录 Cookie 提前加载，保证 AI 工具在用户没打开过音乐页时也能带上 Cookie 搜索。

package me.rerere.rikkahub.data.music

import android.content.Context
import java.io.File

object MusicSession {
    private const val COOKIE_FILE = "music_cookie.txt"

    @Volatile
    private var loaded = false

    fun cookieFile(context: Context): File = File(context.filesDir, COOKIE_FILE)

    /** 从磁盘加载登录 Cookie（幂等）。应在 App 启动以及 AI 搜索前调用。 */
    fun ensure(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val f = cookieFile(context)
                if (f.exists()) NeteaseApi.cookie = f.readText().trim()
            }
            loaded = true
        }
    }

    /** 把当前 Cookie 持久化（登录成功后调用）。 */
    fun save(context: Context) {
        runCatching { cookieFile(context).writeText(NeteaseApi.cookie) }
        loaded = true
    }

    /** 退出登录。 */
    fun clear(context: Context) {
        NeteaseApi.cookie = ""
        runCatching { cookieFile(context).delete() }
        loaded = true
    }
}
