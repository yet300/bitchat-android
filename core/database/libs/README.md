# Vendored SQLCipher (iOS)

`SQLCipher.xcframework` — official prebuilt binary from Zetetic's SPM package
[SQLCipher.swift](https://github.com/sqlcipher/SQLCipher.swift), release **4.16.0**
(exact version parity with the Android `net.zetetic:sqlcipher-android:4.16.0` dependency).

- Source zip: https://github.com/sqlcipher/SQLCipher.swift/releases/download/4.16.0/SQLCipher.xcframework.zip
- SHA-256 of the zip (matches the checksum pinned in the upstream `Package.swift`):
  `510fd00fa51fb017909a159bb1cc233b012e8ce18dc9c2f09014fe47f557c1a6`
- Only the `ios-arm64` and `ios-arm64_x86_64-simulator` slices are vendored (dSYMs and the
  tvOS/watchOS/macOS/catalyst/visionOS slices are dropped); the `Info.plist` is upstream's.
- License: SQLCipher Community Edition, BSD-style (Zetetic LLC).

`:core:database` links its iOS binaries against this framework (see `build.gradle.kts`,
`linkSqlite = false`) so SQLiter's `sqlite3_*` calls resolve to SQLCipher instead of the system
SQLite. A future `iosApp` must add the same SPM package so the dynamic framework is embedded in
the app bundle.

To upgrade: download the new release zip, verify its checksum against the upstream
`Package.swift`, and replace the two slices. Keep the version in lockstep with
`sqlcipher-android` in `gradle/libs.versions.toml`.
