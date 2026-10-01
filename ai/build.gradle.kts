import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// AI 核心逻辑已移到 `:core`（KMP，Android + 桌面共享）。本模块保留为薄壳以兼容既有依赖，
// 并继续承载 ai 的单元测试（测试仍位于 ai/src/test，通过 :core 访问被测类）。
plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.ai"

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    api(project(":core"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
