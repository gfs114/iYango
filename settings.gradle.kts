pluginManagement {
    repositories {
        maven("https://repo.huaweicloud.com/repository/maven/")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                excludeGroupByRegex("com\\.google\\.devtools.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://repo.huaweicloud.com/repository/maven/")
        google()
        mavenCentral()
    }
}

rootProject.name = "iYango"

// 首批 7 个模块（见实施方案 5.2 节），依赖方向：
// app → feature:main / data / platform:android → core:designsystem / core:domain → core:model
include(":app")
include(":core:model")
include(":core:domain")
include(":core:designsystem")
include(":data")
include(":feature:main")
include(":platform:android")
