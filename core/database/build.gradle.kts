import app.cash.sqldelight.gradle.SqlDelightExtension

plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
    alias(libs.plugins.metro)
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.domain)
            implementation(projects.core.common)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.sqlcipher.driver)
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
    // Do NOT auto-link the system sqlite3 into native binaries: sqlcipher-driver's klib bundles a
    // static SQLCipher that must stay the sole sqlite3_* exporter in the final link. With both
    // linked, the two-level-namespace linker could silently bind sqlite3_* to the unencrypted
    // system library.
    linkSqlite.set(false)
    databases {
        create("BitMessageDatabase") {
            packageName.set("com.app.database")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
        }
    }
}
