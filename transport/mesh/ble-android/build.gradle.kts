plugins {
    // Applied by id: the alias carries version "unspecified", which Gradle rejects
    // once build-logic is already on the build classpath via the KMP convention plugin.
    id("com.plugins.androidLibraryPlugin")
}

dependencies {
    api(projects.transport.mesh)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
