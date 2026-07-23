// app/build.gradle.kts

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    id("androidx.room") // Keep this direct ID for the Room plugin
    alias(libs.plugins.hilt.android)
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
}

hilt {
    enableAggregatingTask = false
}

android {
    namespace = "com.pbec.preboardexamchecker"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.pbec.preboardexamchecker"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/COPYING"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE.txt"
            // Eclipse Angus Mail (jakarta.mail + angus-activation) ship duplicate license metadata.
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE.md"
        }
    }
    ndkVersion = "27.0.12077973"
    room {
        schemaDirectory("$projectDir/schemas")
    }
}

configurations.all {
    resolutionStrategy {
        eachDependency { 
            // Force Kotlinx Serialization JSON, Core, and BOM to 1.6.3
            if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-serialization-")) {
                useVersion("1.6.3")
                because("Forcing Kotlinx Serialization libraries to 1.6.3 for Kotlin 1.9.22 compatibility.")
            }

            // Force Kotlinx Coroutines to 1.7.3 (compatible with Kotlin 1.9.x)
            if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-coroutines-")) {
                useVersion("1.7.3")
                because("Downgrading kotlinx-coroutines to 1.7.3 for Kotlin 1.9.22 compatibility.")
            }

            // Force Kotlin Stdlib and its variants to 1.9.22
            if (requested.group == "org.jetbrains.kotlin") {
                val name = requested.name
                if (name == "kotlin-stdlib" || name == "kotlin-stdlib-common" || name == "kotlin-stdlib-jdk8") {
                    useVersion("1.9.22")
                    because("Forcing Kotlin standard library to 1.9.22 for compatibility with current setup.")
                }
            }
        }
    }
    // Keep POI transitive logging API; excluding it causes runtime NoClassDefFoundError.
}

dependencies {
    implementation(libs.opencv)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.poi)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Add Kotlinx Serialization JSON library
    implementation(libs.kotlinx.serialization.json)

    // Room dependencies
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Gson for ListLongConverter
    implementation(libs.gson)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.guava)

    // Hilt dependencies
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // iText7 dependency
    implementation(libs.itext7.kernel)
    implementation(libs.itext7.layout)

    // Material Icons Extended
    implementation(libs.androidx.compose.material.icons.extended)

    // Apache POI OOXML for XLSX files
    implementation(libs.poi.ooxml)

    implementation(libs.androidx.exifinterface)

    // Eclipse Angus Mail (jakarta.mail) + encrypted app-password storage (email slips).
    implementation(libs.angus.mail)
    implementation(libs.angus.activation)
    implementation(libs.jakarta.activation.api)
    implementation(libs.androidx.security.crypto)

    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.espresso.core)
}
