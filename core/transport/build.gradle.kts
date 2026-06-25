plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.common)
            implementation(projects.core.crypto)

            implementation(libs.kotlinx.io.core)
            implementation(libs.kompress.core)

            implementation(libs.cryptography.core)
            implementation(libs.secp256k1.kmp)
            implementation(libs.stately.concurrent.collections)
            implementation(libs.stately.concurrency)
        }
        androidMain.dependencies {

            // ktor HttpClient is part of this module's public surface (HttpClientProvider), so expose it.
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.websockets)

            implementation(libs.cryptography.provider.jdk.bc)
            implementation(libs.secp256k1.jni.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.process)
            implementation(libs.androidx.lifecycle.runtime.ktx)
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
