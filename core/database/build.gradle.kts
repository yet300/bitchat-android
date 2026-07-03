import app.cash.sqldelight.gradle.SqlDelightExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
    alias(libs.plugins.metro)
    alias(libs.plugins.sqldelight)
}

kotlin {
    // Link iOS binaries against the vendored SQLCipher.xcframework (see libs/README.md) instead of
    // the system SQLite: SQLCipher exports the same sqlite3_* symbols SQLiter binds, so no cinterop
    // is needed — only this link-time substitution (plus linkSqlite=false below, which drops the
    // competing `-lsqlite3` SQLDelight would otherwise add).
    targets.withType<KotlinNativeTarget>().configureEach {
        val slice = when (konanTarget) {
            KonanTarget.IOS_ARM64 -> "ios-arm64"
            KonanTarget.IOS_SIMULATOR_ARM64, KonanTarget.IOS_X64 -> "ios-arm64_x86_64-simulator"
            else -> null
        }
        if (slice != null) {
            val frameworkDir = file("libs/SQLCipher.xcframework/$slice").absolutePath
            binaries.configureEach {
                linkerOpts("-F$frameworkDir", "-framework", "SQLCipher", "-rpath", frameworkDir)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.domain)
            implementation(projects.core.common)
            implementation(libs.sqldelight.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.sqlite)
        }
        nativeMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        nativeTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidHostTest.dependencies {
            implementation(libs.bundles.android.testing)
            implementation(libs.kotlin.test)
            implementation(libs.turbine)
            // JVM JDBC SQLite driver (xerial) — drives DAO/schema round-trips on the host JVM, where
            // the SQLCipher Android native libs cannot load. The real encryption proof runs as an
            // instrumented device test instead.
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

// The SQLCipher encryption proof in src/androidDeviceTest/ needs the Android-native SQLCipher libs,
// which cannot load in host-JVM unit tests. Enabling an on-device test source set under this AGP KMP
// plugin is a follow-up; until then that test file is inert (not compiled) and is run by enabling
// `withDeviceTestBuilder { ... }` on the android extension. It is deliberately outside the unit gate.

// SQLDelight schema/queries live in commonMain; the generated multiplatform Database API is consumed
// by the platform driver factories (SQLCipher on both Android and iOS).
extensions.configure<SqlDelightExtension> {
    // Do NOT auto-link the system sqlite3 into native binaries: iOS links the vendored SQLCipher
    // instead (see the KotlinNativeTarget block above). With both linked, the two-level-namespace
    // linker could silently bind sqlite3_* to the unencrypted system library.
    linkSqlite.set(false)
    databases {
        create("BitMessageDatabase") {
            packageName.set("com.app.database")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
        }
    }
}
