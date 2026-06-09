plugins {
    alias(libs.plugins.local.android.library)
    alias(libs.plugins.metro)
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.common)
    implementation(projects.core.crypto)
    implementation(projects.core.transport)

    implementation(libs.bundles.multiplatform.settings)
}
