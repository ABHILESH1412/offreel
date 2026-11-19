plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release version: CI passes -PversionName=1.2.3 (from the release tag). versionCode is derived
// from it (1.2.3 -> 10203) so every release installs over the previous one.
val releaseVersionName = (findProperty("versionName") as String?)?.removePrefix("v") ?: "0.1.0"
val releaseVersionCode = releaseVersionName.split('.', '-').take(3).mapNotNull { it.toIntOrNull() }
    .let { p -> (p.getOrElse(0) { 0 } * 10000) + (p.getOrElse(1) { 0 } * 100) + p.getOrElse(2) { 0 } }
    .coerceAtLeast(1)

android {
    namespace = "dev.offreel"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.abhilesh1412.offreel"
        minSdk = 29
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        ndk {
            // yt-dlp ships a Python runtime per ABI; phones only need arm64.
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Release signing comes from environment variables (GitHub Actions secrets, see docs/releasing.md):
    //   OFFREEL_KEYSTORE (path to the .jks), OFFREEL_STORE_PASSWORD, OFFREEL_KEY_ALIAS, OFFREEL_KEY_PASSWORD.
    // Without them, release builds use the debug key: fine for trying on your own phone, never for publishing.
    val releaseKeystore = System.getenv("OFFREEL_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("OFFREEL_STORE_PASSWORD")
                keyAlias = System.getenv("OFFREEL_KEY_ALIAS") ?: "offreel"
                keyPassword = System.getenv("OFFREEL_KEY_PASSWORD") ?: System.getenv("OFFREEL_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        // Development builds install next to the released app ("offreel dev").
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // R8 drops unused code (mostly the big icon set): ~130 MB debug APK -> much smaller release.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
