plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
    // Temporary: moved classes still carry Metro @Inject metadata until the
    // de-DI phase replaces graph construction with MeshTransport.create().
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.transport.protocol)
            implementation(projects.core.common)
            implementation(projects.core.crypto)

            implementation(libs.kotlinx.io.core)
            implementation(libs.stately.concurrent.collections)
            implementation(libs.stately.concurrency)

            // SHA-256/HMAC (protocol's NostrHashing) + CryptographyRandom (VerificationService).
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
