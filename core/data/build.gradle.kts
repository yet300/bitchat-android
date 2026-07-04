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
            implementation(projects.transport.di)
            implementation(projects.core.database)

            implementation(libs.ksafe)

            // CSPRNG for the platform-free SQLCipher passphrase (DatabaseKeyProviderImpl).
            implementation(libs.cryptography.core)

            // Compass — commonMain forward geocoder (place name → coordinates) for the search Geo tab.
            implementation(libs.compass.geocoder)
            implementation(libs.compass.geocoder.mobile)

            implementation(libs.stately.concurrent.collections)
            implementation(libs.stately.concurrency)
            implementation(libs.kotlinx.io.core)
        }
        androidMain.dependencies {
            implementation(projects.transport.mesh)
            // Platform DI edge (moved from :shared androidMain): AndroidAppForegroundState observes
            // ProcessLifecycleOwner for data-saving throttling.
            implementation(libs.androidx.lifecycle.process)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }
        nativeMain.dependencies {
            implementation(projects.transport.mesh)
        }
        androidHostTest.dependencies {
            implementation(libs.bundles.android.testing)

            implementation(libs.secp256k1.jni.jvm)

            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}
