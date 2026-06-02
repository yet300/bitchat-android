import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

private const val JDK_VERSION = 11

internal fun Project.configureKotlinMultiplatform(
    extension: KotlinMultiplatformExtension,
) = extension.apply {
    jvmToolchain(JDK_VERSION)

    extensions.configure<KotlinMultiplatformAndroidLibraryExtension>("android") {
        val moduleName = path.split(":").drop(2).joinToString(".")
        namespace = if (moduleName.isNotEmpty()) "org.yet.$moduleName" else "org.yet.bitMessage"

        compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()
        minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()

        // Run commonTest on the JVM host (fast) instead of only the iOS simulator.
        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    //common dependencies
    sourceSets.apply {
        commonMain {
            dependencies {
                api(libs.findLibrary("kotlinx-coroutines-core").get())
                api(libs.findLibrary("kotlinx-datetime").get())
                api(libs.findLibrary("kotlinx-serialization-json").get())
            }
        }
        commonTest.dependencies {
            implementation(libs.findBundle("testing").get())
        }
    }
}