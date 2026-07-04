plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
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
            // Android BLE/GATT + Wi-Fi Aware bearer stack (formerly :transport:mesh:ble-android).
            implementation(libs.cryptography.provider.jdk.bc)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.process)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }
        nativeMain.dependencies {
            // Apple CoreBluetooth bearer stack (formerly :transport:mesh:ble-apple).
            implementation(libs.cryptography.provider.apple)
        }
        androidHostTest.dependencies {
            implementation(libs.bundles.android.testing)
        }
    }
}
