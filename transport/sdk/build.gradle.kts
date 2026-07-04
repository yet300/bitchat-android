plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
}

// DI-agnostic SDK facade: aggregates the tor/nostr/mesh clients for the typical
// consumer. No Metro — a plain factory. Advanced consumers depend on the
// individual :transport:tor / :transport:nostr / :transport:mesh modules instead.
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
