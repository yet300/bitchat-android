plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
    // Temporary: moved classes still carry Metro @Inject metadata until the
    // de-DI phase replaces graph construction with TorClient.create().
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.common)

            implementation(libs.stately.concurrency)

            api(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)

            implementation(libs.artitor)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        nativeMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
