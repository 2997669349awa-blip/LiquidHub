// Modified by AI Hello World on 2026-10-03.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// Shizuku 权限与用户服务封装：绑定 IPhoneShell，以 shell 身份执行命令。

package me.rerere.rikkahub.data.phone

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import me.rerere.rikkahub.phone.IPhoneShell
import rikka.shizuku.Shizuku

object ShizukuShell {
    const val REQUEST_CODE = 0x1f01

    @Volatile
    private var shell: IPhoneShell? = null

    @Volatile
    private var bound = false
    private var connection: ServiceConnection? = null

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        isBinderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(requestCode: Int = REQUEST_CODE) {
        runCatching {
            if (isBinderAlive() && !hasPermission()) Shizuku.requestPermission(requestCode)
        }
    }

    /**
     * Removed from Shizuku public API in 13; use the UserService binder instead.
     */
    fun exec(command: String): String? {
        val s = shell ?: return null
        return runCatching { s.exec(command) }.getOrNull()
    }

    fun ensureBound(context: Context) {
        if (bound || !hasPermission()) return
        synchronized(this) {
            if (bound) return
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, PhoneShellService::class.java.name),
            )
                .processNameSuffix("phone_shell")
                .tag("liquidhub_phone_shell")
                .version(1)
                .daemon(false)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    shell = IPhoneShell.Stub.asInterface(service)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    shell = null
                }
            }
            connection = conn
            runCatching { Shizuku.bindUserService(args, conn) }
                .onSuccess { bound = true }
        }
    }
}
