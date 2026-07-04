plugins {
    id("com.plugins.androidLibraryPlugin")
}

dependencies {
    api(projects.transport.mesh)
    implementation(projects.core.common)
    implementation(projects.core.crypto)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
