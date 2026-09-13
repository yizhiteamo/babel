pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Babel"

// UI layer
include(":app")

// Domain layer (pure Kotlin: Android types cannot compile here)
include(":core:model")
include(":core:common")
include(":domain")

// Shared test doubles. Consumed only via testImplementation.
include(":core:testing")

// Data / provider layer
include(":data:settings")
include(":data:translation")

// Platform layer (Android capabilities -> project-owned models)
// Contract shared by platform modules whose interface mentions an Android
// type, so it cannot live in :domain. Implementation-free.
include(":platform:screen")

include(":platform:accessibility")
include(":platform:overlay")
include(":platform:capture")
