plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Test doubles for the contracts in :domain, so pipeline behaviour can be
// verified on the JVM without a device or a live provider
// (docs/systems/testing.md). Consumers depend on this via testImplementation.
dependencies {
    api(project(":domain"))
    api(libs.kotlinx.coroutines.test)
}
