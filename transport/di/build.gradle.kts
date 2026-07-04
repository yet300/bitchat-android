plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
    alias(libs.plugins.metro)
}


kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.transport.protocol)
            api(projects.transport.tor)
            api(projects.transport.nostr)
            api(projects.transport.mesh)
            implementation(projects.core.common)
            implementation(projects.core.crypto)
        }
    }
}
