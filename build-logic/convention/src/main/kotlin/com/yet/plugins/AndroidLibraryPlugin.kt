package com.yet.plugins

import com.android.build.api.dsl.LibraryExtension
import configureAndroidLibrary
import configureAndroidLibraryDependencies
import libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Convention plugin for Android library modules. Applies the Android library + serialization
 * plugins and shares SDK/JVM/dependency configuration. Intended for the Android-specific layers
 * (core:data, core:transport, core:crypto) that wrap BLE/Tor/KSafe/etc.
 *
 * Kotlin support is built into AGP 9, so the standalone `kotlin-android` plugin is intentionally
 * not applied (AGP rejects it). The Kotlin JVM target is derived from `compileOptions` configured
 * in [configureAndroidLibrary].
 */
class AndroidLibraryPlugin : Plugin<Project> {

    override fun apply(target: Project): Unit = with(target) {
        with(pluginManager) {
            apply(libs.findPlugin("android-library").get().get().pluginId)
            apply(libs.findPlugin("kotlin-serialization").get().get().pluginId)
        }

        extensions.configure<LibraryExtension>(::configureAndroidLibrary)
        configureAndroidLibraryDependencies()
    }
}
