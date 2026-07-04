plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
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
