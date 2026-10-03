// Modified by AI Hello World on 2026-10-03.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// Shizuku 用户服务：以 shell(uid 2000) 身份执行命令。由 Shizuku 进程反射实例化，无需在清单声明。

package me.rerere.rikkahub.data.phone

import androidx.annotation.Keep
import me.rerere.rikkahub.phone.IPhoneShell
import java.io.File

@Keep
class PhoneShellService : IPhoneShell.Stub() {
    override fun exec(command: String): String {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .directory(File("/"))
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.take(64_000)
        }.getOrElse { "error: ${it.message}" }
    }
}
