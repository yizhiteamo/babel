plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin on purpose: project-owned models must never expose Android or
// provider types (ADR 004). Keeping this module off the Android plugin makes
// that boundary a compile error instead of a code-review rule.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
}
