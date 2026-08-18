// Drip Manager 根工程：仅声明插件版本（apply false），实际应用在 :app。
plugins {
    alias(libs.plugins.agp.app) apply false
    // 把 Kotlin 版本 pin 到 buildscript classpath（AGP 9 内置 Kotlin，须显式声明版本）。
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
