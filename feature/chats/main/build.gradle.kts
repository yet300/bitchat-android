plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
    alias(libs.plugins.metro)
}

kotlin {

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.common)
            implementation(projects.core.domain)

            implementation(projects.feature.chats.conversations)
            implementation(projects.feature.chats.details)

            implementation(libs.bundles.decompose)
            implementation(libs.bundles.mvi)
        }

        commonTest.dependencies {
            implementation(libs.bundles.testing)
        }
    }
}
