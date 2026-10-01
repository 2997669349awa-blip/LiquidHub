import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// LiquidHub Windows / 桌面端（Compose Multiplatform）。
// 首版：远程 OpenAI 兼容 Provider 的聊天、模型选择、联网搜索开关、右侧抽屉(历史+设置)、液态玻璃风。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "me.rerere.liquidhub"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

compose.desktop {
    application {
        mainClass = "me.rerere.liquidhub.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "LiquidHub"
            packageVersion = "1.1.0"
            description = "LiquidHub Desktop"
            vendor = "LiquidHub"
            windows {
                iconFile.set(project.file("icons/app.ico"))
            }
            linux {
                iconFile.set(project.file("icons/app.png"))
            }
        }
    }
}
