plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.babel.platform.screen"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Contracts only — no implementation, no Hilt, no dependency on :domain.
// The single reason this module exists is that its contract mentions Bitmap,
// which cannot appear in the pure-Kotlin domain.
dependencies {
    implementation(libs.androidx.core.ktx)
}
