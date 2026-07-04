plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.common)
            implementation(projects.core.crypto)

            implementation(libs.kotlinx.io.core)
            implementation(libs.kompress.core)

            // SHA-256 / HMAC primitives (NostrHashing) shared by the nostr and mesh layers.
            implementation(libs.cryptography.core)
        }
        androidMain.dependencies {
            implementation(libs.cryptography.provider.jdk.bc)
        }
        nativeMain.dependencies {
            implementation(libs.cryptography.provider.apple)
        }
        androidHostTest.dependencies {
            implementation(libs.bundles.android.testing)
        }
    }
}
