plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.offreel"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.abhilesh1412.offreel"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // yt-dlp ships a Python runtime per ABI; phones only need arm64.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        // Development builds install next to the released app ("offreel dev").
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // youtubedl-android unpacks its Python/ffmpeg payload from the native lib folder.
            useLegacyPackaging = true
            // These "libraries" are zip payloads, not ELF files; don't try to strip them.
            keepDebugSymbols += "**/*.zip.so"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
