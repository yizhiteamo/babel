plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.babel.platform.capture"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Recognising text in screen images. Frames arrive through :platform:screen;
// taking them is somebody else's job (ADR 009). Like every other platform
// module it cannot see :data:translation, so it cannot reach a provider
// directly (ADR 005).
dependencies {
    implementation(project(":domain"))
    // Consumes frames through the contract; it does not know, and must not
    // know, which platform capability produced them.
    implementation(project(":platform:screen"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Judging real material needs a real device: ML Kit's recogniser and
    // Bitmap both need one. See MangaMaterialEvaluationTest.
    // Test-only for now. Stage 2 asks whether a phone can run the comic models
    // at all; until that is answered the app ships exactly what it shipped
    // before, on the same principle that kept Android plumbing out of the way
    // until the models proved themselves on the desktop.
    // Real dependency now, not just a test one. Stage 2 kept it out of the app
    // until the numbers justified it; they do (`docs/milestones/v2.md`).
    implementation(libs.onnxruntime.android)
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    testImplementation(project(":core:testing"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
