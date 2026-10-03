// Modified by AI Hello World on 2026-10-03.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
package me.rerere.rikkahub.phone;

/**
 * Shizuku 用户服务接口：以 shell(uid 2000) 身份执行命令。
 */
interface IPhoneShell {
    String exec(String command);
}
