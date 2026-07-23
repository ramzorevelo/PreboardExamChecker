// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("androidx.room") version "2.6.1"
        id("org.jetbrains.kotlin.android") version "1.9.22"
        id("com.google.devtools.ksp") version "1.9.22-1.0.18"
        id("com.google.dagger.hilt.android") version "2.51.1"
        id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "PreBoardExamChecker"
include(":app")
