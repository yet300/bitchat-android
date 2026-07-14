import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.metro)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            binaryOption("bundleId", "com.yet.bitmessage.shared")
            isStatic = true
            export(projects.feature.root)
            export(libs.bundles.decompose)
            export(libs.grant.motion)
            export(libs.grant.location.always)
            export(libs.grant.bluetooth)
        }
    }


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
            // Process foreground/background signal for AppForegroundState (#706).
            implementation(libs.androidx.lifecycle.process)
        }
        commonMain.dependencies {
            implementation(projects.core.domain)
            implementation(projects.core.common)
            implementation(projects.core.crypto)
            implementation(projects.transport.di)
            implementation(projects.core.data)
            implementation(projects.core.database)

            api(projects.feature.root)
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

            // FileKit — multiplatform file picker (replaces the androidMain AttachmentPicker actual).
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)

            implementation(libs.kotlinx.io.core)

            api(libs.bundles.decompose)
            implementation(libs.decompose.compose)
            implementation(libs.decompose.compose.experimental)

            implementation(libs.ksafe)

            api(libs.grant.motion)
            api(libs.grant.location.always)
            api(libs.grant.bluetooth)
        }
        iosMain.dependencies {
            // Apple BLE bearer + native transport seams (NativeTransportBindings moved here).
            implementation(projects.transport.mesh)
            // JSON armor for SerializableContainer in the NSCoder state-preservation bridge
            // (StateKeeperUtils); no @Serializable codegen here, so the plugin is not applied.
            implementation(libs.kotlinx.serialization.json)
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
