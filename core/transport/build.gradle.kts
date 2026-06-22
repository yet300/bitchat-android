plugins {
    alias(libs.plugins.local.android.library)
    alias(libs.plugins.metro)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.crypto)

    // ktor HttpClient is part of this module's public surface (HttpClientProvider), so expose it.
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)

    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.jdk)
    implementation(libs.secp256k1.jni.android)
    testImplementation(libs.secp256k1.jni.jvm)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
