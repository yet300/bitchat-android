import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies


/**
 * Shared configuration for plain Android library modules (e.g. core:data, core:transport,
 * core:crypto) that depend on Android APIs (BLE, Tor, KSafe, Context) and therefore are not
 * pure KMP commonMain code.
 */
internal fun Project.configureAndroidLibrary(
    extension: LibraryExtension,
) = extension.apply {
    val moduleName = path.split(":").drop(2).joinToString(".")
    namespace = if (moduleName.isNotEmpty()) "org.yet.$moduleName" else "org.yet.bitMessage"

    compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()

    defaultConfig {
        minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Local unit tests run on the JVM via Robolectric (KSafe/Context need an Android runtime).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

/** Default dependencies shared by Android library modules. */
internal fun Project.configureAndroidLibraryDependencies() {
    dependencies {
        add("api", libs.findLibrary("kotlinx-coroutines-core").get())
        add("api", libs.findLibrary("kotlinx-serialization-json").get())
        add("api", libs.findLibrary("kotlinx-datetime").get())
        add("testImplementation", libs.findBundle("android-testing").get())
    }
}
