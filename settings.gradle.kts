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

// Data / provider layer
include(":data:settings")
include(":data:translation")

// Platform layer (Android capabilities -> project-owned models)
include(":platform:accessibility")
include(":platform:overlay")
