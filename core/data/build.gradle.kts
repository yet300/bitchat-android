plugins {
    alias(libs.plugins.local.android.library)
    alias(libs.plugins.metro)
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.common)
    implementation(projects.core.crypto)
    implementation(projects.core.transport)
    implementation(projects.core.database)

    implementation(libs.bundles.multiplatform.settings)

    // secp256k1-kmp JVM native lib for unit tests that exercise transport's Nostr crypto
    // (the android JNI variant ships only android .so; host JVM tests need the jvm variant).
    testImplementation(libs.secp256k1.jni.jvm)

    // In-memory JDBC SQLite driver for repository tests that round-trip through the DB DAOs.
    testImplementation(libs.sqldelight.sqlite.driver)
}
