plugins {
    alias(libs.plugins.local.android.library)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.crypto)
    implementation(libs.okhttp)
    implementation(libs.bundles.cryptography)
    implementation(libs.androidx.core.ktx)
}
