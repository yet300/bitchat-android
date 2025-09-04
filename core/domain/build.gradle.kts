plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Kotlin coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Date utilities (for models)
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}
