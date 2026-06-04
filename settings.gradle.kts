enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "bitchat-android"

pluginManagement {
    includeBuild("build-logic")
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

include(":app")

include(":core")
include(":core:common")
include(":core:domain")
include(":core:crypto")
include(":core:transport")
include(":core:data")
