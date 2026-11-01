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
        // Personal sideloaded app: bake the API keys from the gitignored .env into every build, as
        // fallbacks for keys not set in Settings. Don't share the APK: anyone with it can read them.
        buildConfigField("String", "SONIOX_API_KEY", "\"${envFileValue("SONIOX_API_KEY")}\"")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${envFileValue("ANTHROPIC_API_KEY")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideloaded app: sign release builds with the debug key.
            signingConfig = signingConfigs.getByName("debug")
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

    testImplementation(libs.junit)
    // Android's org.json is a stub in JVM unit tests; use the real implementation.
    testImplementation(libs.org.json)
}

/** [name] from the repo's .env file (same file tools/stream_test.py reads), or "" if absent. */
fun envFileValue(name: String): String {
    val env = rootProject.file(".env")
    if (!env.exists()) return ""
    val props = Properties().apply { env.reader().use { load(it) } }
    return props.getProperty(name, "").trim().removeSurrounding("\"").removeSurrounding("'")
}
