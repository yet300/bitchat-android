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
        // Local ArtiTor builds (io.github.yet300:tor) until the release reaches Maven Central.
        mavenLocal {
            content { includeGroup("io.github.yet300") }
        }
    }
}

include(":androidApp")
include(":shared")

include(":core")
include(":core:common")
include(":core:domain")
include(":core:crypto")
include(":core:data")
include(":core:database")

include(":transport")
include(":transport:protocol")
include(":transport:tor")
include(":transport:nostr")
include(":transport:mesh")
include(":transport:di")
include(":transport:sdk")

include(":feature")
include(":feature:root")
include(":feature:onboarding")
include(":feature:map")
include(":feature:debug")

include(":feature:chats")
include(":feature:chats:main")
include(":feature:chats:conversations")
include(":feature:chats:details")

