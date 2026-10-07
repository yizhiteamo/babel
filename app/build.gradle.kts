plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.babel.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // The app's identity to Android, and deliberately not the Kotlin package
        // root (`com.babel`) nor the `namespace` above (`com.babel.app`) — the
        // three are independent, and only this one is what the system keys on:
        // component names, `/data/data/<id>`, the external files dir the models
        // live in, and uniqueness on Play. It cannot be changed after a release,
        // which is why it was changed before there was one.
        applicationId = "com.yizhiteamo.babel"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One APK per CPU family. ML Kit and ONNX Runtime ship native code for four
    // of them, and one APK holding all four measured 197MB — three quarters of
    // it libraries the phone installing it cannot run. Almost every phone is
    // arm64-v8a; the others are for older 32-bit phones and emulators.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    // Release signing comes from Gradle properties outside the repository
    // (`~/.gradle/gradle.properties`): the keystore and its password are never
    // committed. Without them a release build is simply left unsigned. The same
    // key must sign every release, or an installed copy cannot be updated.
    val releaseStore = providers.gradleProperty("BABEL_RELEASE_STORE_FILE").orNull
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("BABEL_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("BABEL_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("BABEL_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        // AndroidLogger drops debug logging in release builds.
        buildConfig = true
    }
}

// :app is the only module allowed to see every layer — it exists to wire them
// together. Compose UI here must not contain acquisition or capture logic.
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(project(":data:settings"))
    implementation(project(":data:translation"))
    implementation(project(":platform:accessibility"))
    implementation(project(":platform:capture"))
    implementation(project(":platform:game"))
    implementation(project(":platform:overlay"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
