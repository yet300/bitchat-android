plugins {
    id("com.plugins.androidLibraryPlugin")
    // Temporary: WifiAwareBearer still carries Metro @Inject metadata until the
    // de-DI phase replaces graph construction with a bearer factory call.
    alias(libs.plugins.metro)
}

dependencies {
    api(projects.transport.mesh)
    implementation(projects.core.common)
    implementation(projects.core.crypto)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
