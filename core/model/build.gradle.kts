plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // core:model 是最底层纯 Kotlin 模块：禁止依赖任何 Android/Compose/Room 类型（P1-03）。
    testImplementation(libs.junit)
}
