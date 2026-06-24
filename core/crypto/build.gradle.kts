plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.common)
            implementation(libs.cryptography.core)
            implementation(libs.stately.concurrent.collections)
            implementation(libs.stately.concurrency)
            implementation(libs.ksafe)
        }
        androidMain.dependencies {
            implementation(libs.cryptography.provider.jdk.bc)
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
