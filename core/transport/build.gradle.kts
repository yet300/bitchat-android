plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        // Phase 0 of the KMP migration: every source still lives in androidMain. commonMain stays
        // empty until protocol/model/Nostr code is moved over phase by phase. iOS targets compile
        // (the convention adds iosArm64 + iosSimulatorArm64) but have no transport sources yet.
        androidMain.dependencies {
            implementation(projects.core.common)
            implementation(projects.core.crypto)

            // ktor HttpClient is part of this module's public surface (HttpClientProvider), so expose it.
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.websockets)

            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.jdk.bc)
            implementation(libs.secp256k1.jni.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.process)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }
        androidHostTest.dependencies {
            implementation(libs.secp256k1.jni.jvm)
            implementation(libs.bundles.android.testing)
        }
    }
}
