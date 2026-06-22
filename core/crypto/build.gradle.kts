plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.common)
            implementation(libs.cryptography.core)
        }
        androidMain.dependencies {
            // Tink only (Ed25519 moved off BouncyCastle to cryptography-kotlin in step 4);
            // BouncyCastle stays in :core:transport for Nostr.
            implementation(libs.google.tink.android)
            implementation(libs.cryptography.provider.jdk)
            implementation(libs.androidx.core.ktx)
        }
        nativeMain.dependencies {
            implementation(libs.cryptography.provider.apple)
        }
        androidHostTest.dependencies {
            implementation(libs.bundles.android.testing)
        }
    }
}
