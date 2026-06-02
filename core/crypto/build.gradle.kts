plugins {
    alias(libs.plugins.local.android.library)
}

dependencies {
    implementation(projects.core.common)
    implementation(libs.bundles.cryptography)
    implementation(libs.androidx.core.ktx)
}
