// RikkaHub / LiquidHub 共享核心（Kotlin Multiplatform：Android + JVM/桌面）。
// 这里放与平台无关的 AI 核心逻辑（HTTP/SSE/JSON 工具、Provider、模型、消息模型），
// Android 端与 Windows 桌面端共用同一份代码。
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    androidLibrary {
        namespace = "me.rerere.core"
        compileSdk = 37
        minSdk = 26
    }
    jvm()

    sourceSets {
        val commonMain by getting
        // Android 与 JVM 共享、但使用了 java.* 的代码放这里
        val jvmCommonMain by creating { dependsOn(commonMain) }
        val androidMain by getting { dependsOn(jvmCommonMain) }
        val jvmMain by getting { dependsOn(jvmCommonMain) }

        jvmCommonMain.dependencies {
            api(libs.okhttp)
            api(libs.okhttp.sse)
            api(libs.okhttp.logging)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
        }
    }
}
