import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.plugin.compose)
}

android {
    namespace = "com.teoilie.ascult"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        applicationId = "com.teoilie.ascult"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // Dev convenience until the settings screen exists: bake the key from the gitignored .env
            // into debug builds only. Release builds never contain it.
            buildConfigField("String", "SONIOX_API_KEY", "\"${sonioxApiKeyFromEnvFile()}\"")
        }
        release {
            isMinifyEnabled = false
            // Personal sideloaded app: sign release builds with the debug key.
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "SONIOX_API_KEY", "\"\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/** SONIOX_API_KEY from the repo's .env file (same file tools/stream_test.py reads), or "" if absent. */
fun sonioxApiKeyFromEnvFile(): String {
    val env = rootProject.file(".env")
    if (!env.exists()) return ""
    val props = Properties().apply { env.reader().use { load(it) } }
    return props.getProperty("SONIOX_API_KEY", "").trim().removeSurrounding("\"").removeSurrounding("'")
}
