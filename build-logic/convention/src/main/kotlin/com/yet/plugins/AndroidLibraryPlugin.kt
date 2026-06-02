package com.yet.plugins

import com.android.build.api.dsl.LibraryExtension
import configureAndroidLibrary
import configureAndroidLibraryDependencies
import libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

private const val JDK_VERSION = 11

/**
 * Convention plugin for Android library modules. Applies the Android library + Kotlin Android +
 * serialization plugins and shares SDK/JVM/dependency configuration. Intended for the
 * Android-specific layers (core:data, core:transport, core:crypto) that wrap BLE/Tor/Tink/etc.
 */
class AndroidLibraryPlugin : Plugin<Project> {

    override fun apply(target: Project): Unit = with(target) {
        with(pluginManager) {
            apply(libs.findPlugin("android-library").get().get().pluginId)
            apply(libs.findPlugin("kotlin-android").get().get().pluginId)
            apply(libs.findPlugin("kotlin-serialization").get().get().pluginId)
        }

        extensions.configure<LibraryExtension>(::configureAndroidLibrary)
        extensions.configure<KotlinAndroidProjectExtension> { jvmToolchain(JDK_VERSION) }
        configureAndroidLibraryDependencies()
    }
}
