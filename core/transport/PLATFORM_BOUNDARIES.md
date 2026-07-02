# `:core:transport` — platform boundaries

Status: **commonMain ≈ 90 `.kt`, androidMain = 19, nativeMain = 8, iosMain = 0.** Targets:
`android`, `iosArm64`, `iosSimulatorArm64`. `commonMain` compiles **and links** under both iOS targets
on every change.

`commonMain` owns the whole transport *logic and the bearer facade*: the binary protocol
(`BinaryProtocol`/models/TLV), Nostr (crypto + relay manager + filters + NIP-17/44), GCS/gossip sync,
the mesh graph + route planner, the peer / fragment / store-forward managers, the mesh telemetry port,
the verification QR codec, the Tor orchestrator (`ArtiTorManager`), the ktor HTTP/WebSocket provider
(`HttpClientProvider`), the shared **`BleBearer`** facade + its `BearerTransport` SPI, the shared
`FragmentingPacketSender`, the `MeshNetwork` multiplexer, and the GATT UUIDs / fragmentation / padding
constants (`MeshConstants.Mesh.Gatt`, `MeshConstants.Fragmentation`, `BLEPacketPaddingPolicy`).

Each platform supplies only the **radio/OS plumbing** behind those seams. Coroutine dispatchers in
commonMain go through the injected `com.app.common.AppDispatchers` (never `Dispatchers.IO` directly —
absent on native); managers take `dispatchers: AppDispatchers = AppDispatchers()` and use
`dispatchers.io`.

## Genuinely platform — `androidMain`

Android-only OS surfaces. The Apple counterpart lives in `nativeMain` (or, for Wi-Fi Aware, does not
exist).

### BLE radio (behind the commonMain `BearerTransport` SPI)
- `mesh/BluetoothConnectionManager` — implements commonMain `BearerTransport`; dual-role orchestrator.
- `mesh/BluetoothConnectionTracker` — connection lifecycle/state, eviction policy.
- `mesh/BluetoothGattClientManager`, `BluetoothGattServerManager` — GATT client/server (scan/advertise).
- `mesh/BluetoothPacketBroadcaster` — writes packets onto GATT characteristics (actor-serialized).
- `mesh/BluetoothPermissions`, `BluetoothPermissionManager` — `android.Manifest` runtime permissions.
- `mesh/PowerManager` — `BatteryManager` + `ProcessLifecycleOwner` adaptive duty-cycling.
- `mesh/AndroidBleBearerFactory` — wires `BluetoothConnectionManager` into the shared `BleBearer`.

Apple counterpart: `nativeMain/mesh/CoreBluetoothConnectionManager` implements the same
`BearerTransport`; `NativeBleBearerFactory` wires it into the same commonMain `BleBearer`. The GATT
service/characteristic UUIDs come from commonMain `MeshConstants.Mesh.Gatt` (byte-identical), and
fragmentation/padding/(de)serialization are reused verbatim from commonMain — so the wire bytes match.

### Wi-Fi Aware (Android-only, no Apple counterpart)
- `mesh/WifiAwareBearer` — implements commonMain `MeshBearer` over `android.net.wifi.aware`.
- `mesh/aware/WifiAwareSupport`, `WifiAwareConnectionTracker`, `MeshConnectionTracker`, `SyncedSocket`.

Apple counterpart: **none** — Wi-Fi Aware does not exist on iOS. Apple ships only the BLE bearer.

### Foreground-service-owned mesh lifecycle
- `mesh/BluetoothMeshService` — the FGS owner of the mesh lifecycle (hard project invariant), holds
  `Context`, and supplies the commonMain `NicknameSource` / `ServiceNotifier` ports. Consumes the
  commonMain `MeshNetwork` + `BleBearer` as its single data path.

Apple counterpart: a background-mode CoreBluetooth coordinator (not written yet — see *iOS follow-ups*);
the orchestration it threads through is already commonMain.

### File + engine platform glue
- `features/file/FileUtils` — `Context` cacheDir + `ContentResolver`/`Uri` + `java.io.File`.
- `features/file/AndroidIncomingFileStore` — implements commonMain `IncomingFileStore` over `FileUtils`.
- `net/AndroidHttpEngine` — exposes the OkHttp `HttpClientEngineFactory` for the commonMain
  `HttpClientProvider` (keeps `ktor-client-okhttp` an androidMain detail).
- `nostr/AndroidRelayDirectoryStorage` — assets + filesDir behind the `RelayDirectoryStorage` seam.

## Genuinely platform — `nativeMain` (Apple / CoreBluetooth)

Real `platform.CoreBluetooth` + Foundation. Compiles + links under `iosArm64` and
`iosSimulatorArm64`. (The simulator has **no BLE radio**, so on-device CoreBluetooth wire parity is a
manual verification step, not a CI guarantee.)

- `mesh/CoreBluetoothConnectionManager` — `BearerTransport` over `CBCentralManager` +
  `CBPeripheralManager` (dual-role).
- `mesh/NativeBleBearerFactory` — builds the shared `BleBearer` over the CoreBluetooth stack.
- `mesh/CoreBluetoothData` — `NSData` ↔ `ByteArray` bridges (byte-preserving).
- `net/DarwinHttpEngine` — Darwin (`NSURLSession`) ktor `HttpClientEngineFactory`.
- `platform/NativeAppDirectories` — caches / application-support roots + Arti/Tor data dir.
- `nostr/NativeRelayDirectoryStorage` — bundle resource + caches dir behind `RelayDirectoryStorage`.
- `features/file/NativeIncomingFileStore` — `IncomingFileStore` over kotlinx-io `SystemFileSystem`.
- `di/NativeTransportBindings` — Metro `@BindingContainer` wiring the native providers.

## Seam inventory (commonMain contract → per-platform impl)

| commonMain seam | androidMain impl | nativeMain impl (Apple) |
| --- | --- | --- |
| `mesh/BleBearer` facade + `BearerTransport` | `BluetoothConnectionManager` (+ `AndroidBleBearerFactory`) | `CoreBluetoothConnectionManager` (+ `NativeBleBearerFactory`) |
| `mesh/MeshBearer` (Wi-Fi Aware) | `WifiAwareBearer` | — (no Apple counterpart) |
| `IncomingFileStore` | `AndroidIncomingFileStore` | `NativeIncomingFileStore` |
| `RelayDirectoryStorage` | `AndroidRelayDirectoryStorage` | `NativeRelayDirectoryStorage` |
| `WebSocketClientProvider` / `HttpClientEngineFactory` | `HttpClientProvider` (common) + OkHttp factory | `HttpClientProvider` (common) + Darwin factory |
| `TorDataDirProvider` | DI filesDir path | `NativeAppDirectories.nativeArtiDataDir()` |
| `NicknameSource`, `ServiceNotifier` | `BluetoothMeshService` (FGS) | *(deferred — iOS coordinator)* |

`ArtiTorManager` and `HttpClientProvider` are already commonMain; each platform only supplies the data
dir and the ktor engine factory.

## iOS follow-ups (deferred — not written in this pass)

- **`iosMain` Keychain / Secure Enclave actual** for secret storage (`SecureIdentityStateManager` in
  `:core:crypto`). Until it exists an iOS build has no hardware-backed secret store — required before
  any iOS release. `iosMain` source set is still empty; the CoreBluetooth stack lives in `nativeMain`
  (shared across the Apple targets), and the remaining iOS-specific actuals (Keychain, `UIApplication`
  background/state-restoration) are the outstanding work.
- **iOS mesh coordinator** (`NicknameSource` / `ServiceNotifier` + background-mode CoreBluetooth
  lifecycle) — the orchestration is already commonMain; only the platform lifecycle wrapper is missing.
- **On-device CoreBluetooth wire parity** with the iOS bitchat client — must be verified on hardware;
  the simulator has no BLE radio.
