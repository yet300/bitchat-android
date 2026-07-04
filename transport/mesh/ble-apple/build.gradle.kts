plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// Apple-only bearer module: no Android target, so the shared KMP convention
// plugin (which always configures an Android library) does not apply here.
kotlin {
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(projects.transport.mesh)
            implementation(projects.core.common)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
