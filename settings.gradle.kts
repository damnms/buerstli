pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "buerstli"

include(":app")
include(":domain")          // pure Kotlin: the toothbrush model and ports, no Android
include(":drivers:laifen")  // pure Kotlin: Laifen protocol, depends only on :domain
include(":drivers:sonicare") // pure Kotlin: Philips Sonicare (classic GATT), read-only
