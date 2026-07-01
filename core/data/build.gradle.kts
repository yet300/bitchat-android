plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.domain)
            implementation(projects.core.common)
            implementation(projects.core.crypto)
            implementation(projects.core.transport)
            implementation(projects.core.database)

            implementation(libs.ksafe)

            // CSPRNG for the platform-free SQLCipher passphrase (DatabaseKeyProviderImpl).
            implementation(libs.cryptography.core)

            implementation(libs.stately.concurrent.collections)
            implementation(libs.stately.concurrency)
            implementation(libs.kotlinx.io.core)
        }
        androidMain.dependencies {
            // Platform DI edge (moved from :shared androidMain): AndroidAppForegroundState observes
            // ProcessLifecycleOwner for data-saving throttling.
            implementation(libs.androidx.lifecycle.process)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }
        androidHostTest.dependencies {
            implementation(libs.bundles.android.testing)

            implementation(libs.secp256k1.jni.jvm)

            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}
