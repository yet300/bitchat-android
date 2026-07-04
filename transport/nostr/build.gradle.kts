plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.transport.protocol)
            api(projects.transport.tor)
            implementation(projects.core.common)
            implementation(projects.core.crypto)

            implementation(libs.cryptography.core)
            implementation(libs.secp256k1.kmp)
            implementation(libs.stately.concurrent.collections)
            implementation(libs.stately.concurrency)
        }
        androidMain.dependencies {
            implementation(libs.cryptography.provider.jdk.bc)
            implementation(libs.secp256k1.jni.android)
        }
        nativeMain.dependencies {
            implementation(libs.cryptography.provider.apple)
        }
        androidHostTest.dependencies {
            implementation(libs.secp256k1.jni.jvm)
            implementation(libs.bundles.android.testing)
        }
    }
}
