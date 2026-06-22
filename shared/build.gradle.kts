import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.metro)
}

kotlin {
//    listOf(
//        iosArm64(),
//        iosSimulatorArm64()
//    ).forEach { iosTarget ->
//        iosTarget.binaries.framework {
//            baseName = "Shared"
//            isStatic = true
//        }
//    }
    
    androidLibrary {
       namespace = "com.yet.bitmessage.shared"
       compileSdk = libs.versions.compileSdk.get().toInt()
       minSdk = libs.versions.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_17
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            // QR scanner (I7b-2b-2): CameraX preview + ML Kit barcode analysis.
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.compose)
            implementation(libs.mlkit.barcode.scanning)
            // Attachment picker (P10): system GetContent launcher.
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(projects.core.domain)
            implementation(projects.core.common)
            implementation(projects.core.crypto)
            implementation(projects.core.transport)
            implementation(projects.core.data)

            implementation(projects.feature.root)
            implementation(projects.feature.onboarding)
            implementation(projects.feature.map)
            implementation(projects.feature.debug)
            implementation(projects.feature.chats.main)
            implementation(projects.feature.chats.conversations)
            implementation(projects.feature.chats.details)

            implementation(libs.kotlinx.datetime)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.coil.compose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(libs.qrose)
            implementation(libs.maplibre.compose)

            implementation(libs.bundles.decompose)
            implementation(libs.decompose.compose)
            implementation(libs.decompose.compose.experimental)

            implementation(libs.bundles.multiplatform.settings)

        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
compose.resources {
    packageOfResClass = "com.yet.bitmessage.shared.resources"
}
