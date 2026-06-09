plugins {
    alias(libs.plugins.local.android.library)
    alias(libs.plugins.metro)
}

dependencies {
    implementation(projects.core.common)
    implementation(libs.bundles.cryptography)
    implementation(libs.androidx.core.ktx)
}
