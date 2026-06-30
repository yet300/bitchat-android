# `:core:transport` — platform boundaries

Status after the `transport-kmp2` migration: **commonMain ≈ 86 `.kt`, androidMain = 20, iosMain = 0.**

`commonMain` now owns the whole transport *logic*: the binary protocol (`BinaryProtocol`/models/TLV),
Nostr (crypto + relay manager + filters + NIP-17/44), GCS/gossip sync, the mesh graph + route planner,
the peer / fragment / store-forward managers, the mesh telemetry port, the verification QR codec,
the Tor orchestrator (`ArtiTorManager`) and the ktor HTTP/WebSocket provider (`HttpClientProvider`).

What stays in `androidMain` is either **genuinely platform** (no portable equivalent — it *is* the
OS API) or **deferred** (portable, but not yet moved). iOS actuals are intentionally **not** written
in this pass; the seams below are the entry points an iOS port implements later.

## Genuinely platform — stays `androidMain` (group F)

These wrap Android-only OS surfaces. iOS would implement the same commonMain seam with a different
mechanism (or, for Wi-Fi Aware, not at all — it has no iOS counterpart).

### BLE stack (`MeshBearer` over Bluetooth LE)
- `mesh/BleBearer` — implements commonMain `MeshBearer`; the GATT-backed transport bearer.
- `mesh/BluetoothConnectionManager`, `BluetoothConnectionTracker` — connection lifecycle/state.
- `mesh/BluetoothGattClientManager`, `BluetoothGattServerManager` — GATT client/server (scan/advertise).
- `mesh/BluetoothPacketBroadcaster` — writes packets onto GATT characteristics.
- `mesh/MeshGattConstants` — GATT service/characteristic UUIDs (BLE-only; see *Deferred*).
- `mesh/BluetoothPermissions`, `BluetoothPermissionManager` — `android.Manifest` runtime permissions.
- `mesh/PowerManager` — `BatteryManager` + `ProcessLifecycleOwner` adaptive duty-cycling.

iOS equivalent: CoreBluetooth (`CBCentralManager`/`CBPeripheralManager`) behind the same `MeshBearer`.

### Wi-Fi Aware (Android-only, no iOS counterpart)
- `mesh/WifiAwareBearer` — implements `MeshBearer` over `android.net.wifi.aware`.
- `mesh/aware/WifiAwareSupport`, `WifiAwareConnectionTracker`, `MeshConnectionTracker`, `SyncedSocket`.

iOS equivalent: **none** — Wi-Fi Aware does not exist on iOS. iOS ships only the BLE bearer.

### Foreground-service-owned mesh lifecycle
- `mesh/BluetoothMeshService` — the FGS owner of the mesh lifecycle (hard project invariant), holds
  `Context`, and supplies the commonMain `NicknameSource` / `ServiceNotifier` ports.

iOS equivalent: a background-mode CoreBluetooth coordinator; the orchestration logic it threads
through is already commonMain.

### File + engine platform glue
- `features/file/FileUtils` — `Context` cacheDir + `ContentResolver`/`Uri` + `java.io.File`.
- `features/file/AndroidIncomingFileStore` — implements commonMain `IncomingFileStore` over `FileUtils`.
- `net/AndroidHttpEngine` — exposes the OkHttp `HttpClientEngineFactory` for the commonMain
  `HttpClientProvider` (keeps `ktor-client-okhttp` an androidMain detail).

iOS equivalent: an `IncomingFileStore` over the iOS file APIs, and a Darwin `HttpClientEngineFactory`.

## Deferred — portable, not yet moved (not genuinely platform)

- **`mesh/MeshGattConstants`** — trivially portable (`java.util.UUID` → `kotlin.uuid.Uuid`), but the
  values are BLE GATT UUIDs consumed only by the androidMain BLE stack, so moving them has little
  value until an iOS BLE bearer needs them. Move alongside the iOS BLE work.

> Done since the first cut of this doc: `debug/DebugSettingsManager` moved to commonMain (its
> `ConcurrentLinkedQueue` usage replaced by a small `ConcurrentFifoQueue` = ArrayDeque + stately
> Lock); `nostr/RelayDirectory` moved to commonMain over **kotlinx-io** `SystemFileSystem`/`Path`
> (already a project dependency — no okio needed), with the bundled-asset + filesDir-cache half
> inverted behind the `RelayDirectoryStorage` seam (`AndroidRelayDirectoryStorage`).

## iOS seam inventory (entry points for a later iOS port)

commonMain interfaces with an androidMain implementation today — an iOS target implements an actual
for each (no transport logic needs rewriting):

| commonMain seam | androidMain impl | iOS impl mechanism |
| --- | --- | --- |
| `mesh/MeshBearer` | `BleBearer` (+ `WifiAwareBearer`) | CoreBluetooth (BLE only) |
| `IncomingFileStore` | `AndroidIncomingFileStore` | iOS file APIs |
| `RelayDirectoryStorage` | `AndroidRelayDirectoryStorage` | iOS bundled CSV + cache dir (RelayDirectory itself is commonMain) |
| `WebSocketClientProvider` / `HttpClientEngineFactory` | `HttpClientProvider` (common) + OkHttp factory | Darwin engine factory |
| `NicknameSource`, `ServiceNotifier` | `BluetoothMeshService` | iOS mesh coordinator |
| `SocksAddressSource` / `TorDataDirProvider` / `TorHttpReset` | `ArtiTorManager` (common) / DI filesDir / `HttpClientProvider` | iOS data-dir; Tor client is already KMP |

`ArtiTorManager` and `HttpClientProvider` are already commonMain; iOS only needs to provide the
`TorDataDirProvider` path and a Darwin `HttpClientEngineFactory`.
