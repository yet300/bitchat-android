plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Shim: re-export the extracted SDK modules until all consumers
            // depend on transport:* directly and :core:transport is retired.
            api(projects.transport.protocol)
            api(projects.transport.tor)
            api(projects.transport.nostr)
            api(projects.transport.mesh)

            implementation(projects.core.common)
            implementation(projects.core.crypto)

            implementation(libs.kotlinx.io.core)
            implementation(libs.kompress.core)

            implementation(libs.cryptography.core)
            implementation(libs.secp256k1.kmp)
            implementation(libs.stately.concurrent.collections)
            implementation(libs.stately.concurrency)

            api(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)

            implementation(libs.artitor)
        }
        androidMain.dependencies {

            implementation(libs.ktor.client.okhttp)

            implementation(libs.cryptography.provider.jdk.bc)
            implementation(libs.secp256k1.jni.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.process)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }
        nativeMain.dependencies {
            implementation(libs.cryptography.provider.apple)
            implementation(libs.ktor.client.darwin)
        }
        androidHostTest.dependencies {
            implementation(libs.secp256k1.jni.jvm)
            implementation(libs.bundles.android.testing)
        }
    }
}
