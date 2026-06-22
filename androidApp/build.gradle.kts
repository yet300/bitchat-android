plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.yet.bitmessage.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bitchat.droid"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 33
        versionName = "1.7.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    buildTypes {
        debug {
            ndk {
                // Include x86_64 for emulator support during development
                abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // APK splits for GitHub releases - creates arm64, x86_64, and universal APKs
    // AAB for Play Store handles architecture distribution automatically
    // Auto-detects: splits enabled for assemble tasks, disabled for bundle tasks
    // Works in Android Studio GUI and CLI without needing extra properties
    val enableSplits = gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("assemble", ignoreCase = true) &&
        !taskName.contains("bundle", ignoreCase = true)
    }

    splits {
        abi {
            isEnable = enableSplits
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            isUniversalApk = true  // For F-Droid and fallback
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Project modules
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(projects.core.crypto)
    implementation(projects.core.transport)
    implementation(projects.core.data)

    // Phase C CMP UI + feature modules (their Metro @ContributesTo bindings aggregate
    // into AndroidAppGraph so rootFactory resolves; RootComponent type for the activity).
    implementation(projects.shared)
    implementation(projects.feature.root)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.map)
    implementation(projects.feature.debug)
    implementation(projects.feature.chats.main)
    implementation(projects.feature.chats.conversations)
    implementation(projects.feature.chats.details)

    implementation(libs.decompose)

    implementation(libs.metrox.android)

    // Core Android dependencies. The Compose UI itself lives in :shared (CMP); this module only
    // needs activity-compose to host it via setContent { App(...) }.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Lifecycle
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.lifecycle.process)

    // ObservableSettings type referenced by AndroidAppBindings.provideNicknameSource.
    implementation(libs.bundles.multiplatform.settings)

    // JSON / serialization (Decompose Config) + coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.android)

    // Google Play Services Location
    implementation(libs.gms.location)

    // EXIF orientation handling for images
    implementation(libs.androidx.exifinterface)
    
    // Testing
    testImplementation(libs.bundles.android.testing)
}
