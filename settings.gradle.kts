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
        // Guardian Project raw GitHub Maven (hosts info.guardianproject:arti-mobile-ex)
        maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
    }
}

rootProject.name = "bitchat-android"
include(":app")
// Using published Arti AAR; local module not included
include(":core")
include(":core:domain")
include(":core:data")
include(":core:network")
include(":core:crypto")
include(":core:bluetooth")


include(":feature:chat")
include(":feature:onboarding")
include(":feature:nostr")
