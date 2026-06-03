plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
    alias(libs.plugins.metro)
}

kotlin {

    sourceSets {
        commonMain.dependencies {
            implementation(libs.bundles.decompose)
            implementation(libs.bundles.mvi)
            // Exposed so consumers can use the shared Settings API (appSettings).
            api(libs.bundles.multiplatform.settings)
        }
    }
}