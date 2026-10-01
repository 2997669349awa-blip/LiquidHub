package me.rerere.common.http

import java.util.Locale

/**
 * Android 专用：从 Context 的资源配置读取系统 Locale 列表并构建 Accept-Language。
 * 放在 androidMain，保持共享的 [AcceptLanguageBuilder] 不依赖 Android。
 */
fun AcceptLanguageBuilder.Companion.fromAndroid(
    context: android.content.Context,
    options: AcceptLanguageBuilder.Options = AcceptLanguageBuilder.Options(),
): AcceptLanguageBuilder {
    val cfg = context.resources.configuration
    val locales: List<Locale> = if (android.os.Build.VERSION.SDK_INT >= 24) {
        val list = cfg.locales
        (0 until list.size()).map { idx -> list[idx] }
    } else {
        listOf(cfg.locale)
    }
    return AcceptLanguageBuilder.withLocales(locales, options)
}
