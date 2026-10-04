// Modified by AI Hello World on 2026-10-04.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 输入框命令：在对话框输入以 // 开头的命令即可本地执行，不会发给 AI。大小写不敏感。

package me.rerere.rikkahub.utils

data class ChatCommandSpec(
    val usage: String,
    val description: String,
)

object ChatCommandSpecs {
    const val HEADER = "在输入框以 // 开头输入命令，本地执行、不发给 AI；大小写不敏感，命令与参数之间可用 - 或空格。"

    val commands: List<ChatCommandSpec> = listOf(
        ChatCommandSpec("//help", "显示全部命令"),
        ChatCommandSpec("//tasks", "列出进行中的任务（任务 ID + 会话 ID）"),
        ChatCommandSpec("//kill", "停止当前会话正在进行的生成"),
        ChatCommandSpec("//kill <任务ID或会话ID>", "停止指定任务；ID 取 //tasks 输出的值，也可写成 //kill-<ID>"),
        ChatCommandSpec("//killall", "停止全部进行中的任务"),
        ChatCommandSpec("//recover <任务ID或会话ID>", "对指定任务重新生成；也可写成 //recover-<ID>"),
        ChatCommandSpec("//stop", "停止当前会话的生成（等同 //kill）"),
        ChatCommandSpec("//status", "显示版本、进行中任务数、手机控制状态"),
    )

    fun helpText(): String =
        buildString {
            appendLine("LiquidHub 命令")
            appendLine(HEADER)
            commands.forEach { appendLine("${it.usage}  —  ${it.description}") }
        }.trim()
}
