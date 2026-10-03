// Modified by AI Hello World on 2026-10-03.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 工作区存储位置：优先放在公共存储 /sdcard/LiquidHub/workspaces，卸载重装不丢；
// 未授予「所有文件访问」时回退到应用私有目录。

package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

object WorkspaceStorage {
    private const val TAG = "WorkspaceStorage"
    private const val PUBLIC_FOLDER = "LiquidHub"
    private const val WORKSPACES = "workspaces"

    fun publicBaseDir(): File =
        File(Environment.getExternalStorageDirectory(), "$PUBLIC_FOLDER/$WORKSPACES")

    fun privateBaseDir(context: Context): File = File(context.filesDir, WORKSPACES)

    /** API 30+ 需要「所有文件访问」；我们只在系统真正授予时才用公共目录。 */
    fun isPublicStorageEnabled(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** 用户文件区(files)的根目录：授权后用公共存储，否则用私有目录。 */
    fun resolveFilesBaseDir(context: Context): File =
        if (isPublicStorageEnabled()) publicBaseDir() else privateBaseDir(context)

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

    /**
     * 首次拿到「所有文件访问」后，把私有目录里各工作区的 files/ 用户文件搬到公共目录。
     * rootfs(linux)/tmp 保留在私有目录。同名文件目录已存在则跳过，避免覆盖。
     */
    fun migrateIfNeeded(context: Context) {
        if (!isPublicStorageEnabled()) return
        val privateRoot = privateBaseDir(context)
        if (!privateRoot.isDirectory) return
        val publicRoot = publicBaseDir()
        privateRoot.listFiles()?.filter { it.isDirectory }?.forEach { workspace ->
            val sourceFiles = File(workspace, "files")
            if (!sourceFiles.isDirectory) return@forEach
            val targetFiles = File(File(publicRoot, workspace.name), "files")
            if (targetFiles.exists()) return@forEach
            runCatching {
                targetFiles.parentFile?.mkdirs()
                copyDirectory(sourceFiles.toPath(), targetFiles.toPath())
                sourceFiles.deleteRecursively()
                Log.i(TAG, "Migrated workspace files ${workspace.name} to public storage")
            }.onFailure {
                Log.w(TAG, "Failed to migrate workspace files ${workspace.name}", it)
                runCatching { targetFiles.deleteRecursively() }
            }
        }
    }

    private fun copyDirectory(source: java.nio.file.Path, target: java.nio.file.Path) {
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    destination.parent?.let { Files.createDirectories(it) }
                    Files.copy(
                        path,
                        destination,
                        LinkOption.NOFOLLOW_LINKS,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }
        }
    }
}
