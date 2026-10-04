// Modified by AI Hello World on 2026-10-04.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 输入框命令：在对话框输入以 // 开头的命令即可本地执行，不会发给 AI。

package me.rerere.rikkahub.utils

data class ChatCommandSpec(
    val usage: String,
    val description: String,
)

object ChatCommandSpecs {
    val commands: List<ChatCommandSpec> = listOf(
        ChatCommandSpec("//help", "显示全部命令"),
        ChatCommandSpec("//tasks", "列出进行中的任务及其任务 ID"),
        ChatCommandSpec("//kill-<任务ID>", "强行停止指定任务（资源异常/卡住时使用）"),
        ChatCommandSpec("//killall", "停止全部进行中的任务"),
        ChatCommandSpec("//recover-<任务ID>", "对指定任务尝试重新生成"),
        ChatCommandSpec("//stop", "停止当前会话的生成"),
        ChatCommandSpec("//status", "显示版本、进行中任务数、手机控制状态"),
    )

    fun helpText(): String = commands.joinToString("\n") { "${it.usage}  ${it.description}" }
}
