plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// 发布签名：由环境变量注入（CI Secrets 或本地临时变量），缺省时 release 产出未签名 APK。
val releaseKeystorePath = providers.environmentVariable("IYANGO_KEYSTORE_PATH")
val releaseKeystorePassword = providers.environmentVariable("IYANGO_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("IYANGO_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("IYANGO_KEY_PASSWORD")
val hasReleaseSigning = releaseKeystorePath.isPresent &&
    releaseKeystorePassword.isPresent &&
    releaseKeyAlias.isPresent &&
    releaseKeyPassword.isPresent

android {
    namespace = "com.gfs.iyango"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gfs.iyango"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.2"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath.get())
                storePassword = releaseKeystorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }
    buildTypes {
        debug {
            // 测试包与稳定包可同时安装、互不覆盖（P0-08）。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlinx.**", "okhttp3.**", "okio.**")
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
        }
    }
    buildFeatures {
        compose = true
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP 9 内置 Kotlin：通过 kotlin.compilerOptions 设定字节码目标。
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability.conf"))
}

// AGP 9 不再提供 outputFileName：assembleRelease 结束后把产物改名为 iYango-release*.apk。
val releaseApkDir = layout.buildDirectory.dir("outputs/apk/release")

val renameReleaseApk = tasks.register("renameReleaseApk") {
    group = "build"
    description = "把 app-release*.apk 重命名为 iYango-release*.apk"
    doLast {
        val dir = releaseApkDir.get().asFile
        dir.listFiles { file -> file.isFile && file.name.startsWith("app-release") }
            ?.forEach { file ->
                val target = File(dir, file.name.replaceFirst("app-release", "iYango-release"))
                if (!file.renameTo(target)) {
                    file.copyTo(target, overwrite = true)
                    file.delete()
                }
            }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(renameReleaseApk)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
    implementation(project(":data"))
    implementation(project(":feature:main"))
    implementation(project(":platform:android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // MainActivity 的 ThemeViewModel 经 hiltViewModel() 获取。
    implementation(libs.hilt.navigation.compose)

    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
