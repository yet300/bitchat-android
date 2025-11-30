# Tech Stack

## Build System
- Gradle with Kotlin DSL
- Version catalog: `gradle/libs.versions.toml`
- Min SDK: 26 (Android 8.0)
- Target SDK: 34
- Compile SDK: 35

## Languages & Frameworks
- Kotlin 2.2.0
- Jetpack Compose with Material Design 3
- Kotlin Coroutines for async operations

## Architecture Libraries
- Decompose: Navigation and component lifecycle
- MVIKotlin: State management (MVI pattern)
- Essenty: Lifecycle, state keeper, instance keeper
- Koin: Dependency injection with annotations

## Key Dependencies
- BouncyCastle + Tink: Cryptography (X25519, Ed25519, AES-GCM)
- Nordic BLE Library: Bluetooth LE operations
- OkHttp: WebSocket/HTTP networking
- Arti Mobile: Tor integration
- kotlinx-serialization: JSON serialization

## Common Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Build app bundle (Play Store)
./gradlew bundleRelease

# Install debug build on device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run Android instrumented tests
./gradlew connectedAndroidTest

# Clean build
./gradlew clean

# Check for lint issues
./gradlew lint
```

## Code Style
- Java 1.8 compatibility (jvmTarget)
- ProGuard enabled for release builds
- Lint baseline in `app/lint-baseline.xml`
