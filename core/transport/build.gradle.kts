plugins {
    alias(libs.plugins.local.android.library)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.crypto)
}
