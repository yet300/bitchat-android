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

            implementation(libs.stately.concurrent.collections)
            implementation(libs.stately.concurrency)
            implementation(libs.kotlinx.io.core)
        }
        androidHostTest.dependencies {
            implementation(libs.bundles.android.testing)

            implementation(libs.secp256k1.jni.jvm)

            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}
