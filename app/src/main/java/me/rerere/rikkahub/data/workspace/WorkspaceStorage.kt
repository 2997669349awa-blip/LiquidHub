// Modified by AI Hello World on 2026-10-03.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 工作区公共存储位置与「所有文件访问」授权入口。
// 是否把某个工作区的用户文件放到公共存储由 WorkspaceManager 的每工作区标记决定。

package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

object WorkspaceStorage {
    private const val PUBLIC_FOLDER = "LiquidHub"
    private const val WORKSPACES = "workspaces"

    fun publicBaseDir(): File =
        File(Environment.getExternalStorageDirectory(), "$PUBLIC_FOLDER/$WORKSPACES")

    fun privateBaseDir(context: Context): File = File(context.filesDir, WORKSPACES)

    /** API 30+ 需要「所有文件访问」；我们只在系统真正授予时才允许把工作区放到公共目录。 */
    fun isPublicStorageEnabled(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun requestAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(appIntent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
