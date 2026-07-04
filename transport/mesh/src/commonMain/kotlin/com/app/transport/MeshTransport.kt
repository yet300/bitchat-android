package com.app.transport

import com.app.common.AppDispatchers
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.transport.debug.DebugPreferenceManager
import com.app.transport.features.file.IncomingFileStore
import com.app.transport.mesh.BleBearer
import com.app.transport.mesh.FragmentManager
import com.app.transport.mesh.MeshBearer
import com.app.transport.mesh.MeshCoordinator
import com.app.transport.mesh.MeshLifecycleController
import com.app.transport.mesh.MeshNetwork
import com.app.transport.mesh.MeshService
import com.app.transport.mesh.TransferProgressManager
import com.app.transport.meshgraph.MeshGraphService
import com.app.transport.notification.ServiceNotifier

/**
 * App-side SPIs the mesh calls back into. All six are implemented by the consuming application (the
 * UI store, nickname source, favorite mapping, geohash read-receipt routing, QR verification, and
 * the background service notifier).
 */
class MeshCallbacks(
    val incomingSink: IncomingMessageSink,
    val nicknameSource: NicknameSource,
    val favoriteNostrLink: FavoriteNostrLink,
    val readReceiptRouter: GeohashReadReceiptRouter,
    val verificationService: VerificationService,
    val serviceNotifier: ServiceNotifier,
)

/** Persistence/telemetry seams the mesh engine reads and writes. */
class MeshStores(
    val seenMessageStore: SeenMessageStore,
    val incomingFileStore: IncomingFileStore,
    val telemetry: MeshTelemetry,
    val debugPreferenceManager: DebugPreferenceManager,
)

/**
 * Shared engine collaborators handed to the bearer builder so every bearer (BLE, Wi-Fi Aware,
 * CoreBluetooth) reuses the same fragment reassembly state and transfer progress bookkeeping.
 */
class MeshBearerScope(
    val myPeerID: String,
    val telemetry: MeshTelemetry,
    val fragmentManager: FragmentManager,
    val transferProgressManager: TransferProgressManager,
)

/**
 * The bearers a consumer builds for its platform: [primary] is the BLE bearer that the coordinator
 * drives directly; [additional] are extra radios (e.g. Android Wi-Fi Aware) multiplexed alongside it.
 */
class MeshBearers(
    val primary: BleBearer,
    val additional: Set<MeshBearer> = emptySet(),
)

/**
 * Configuration for [MeshTransport.create]. The platform BLE stack is supplied through [bearers]: a
 * builder that receives the shared engine collaborators and returns the constructed radios, so this
 * commonMain module never references the Android/Apple BLE code.
 *
 * @property encryption Noise identity/session service (also yields the 16-hex mesh peer id).
 * @property fingerprints peer fingerprint manager shared with the identity repository.
 * @property callbacks the six app SPIs (see [MeshCallbacks]).
 * @property stores persistence/telemetry seams (see [MeshStores]).
 * @property bearers builds the platform bearers from the shared [MeshBearerScope]; the first is the
 *   primary BLE bearer. On Android: `MeshBearers(createAndroidBleBearer(context, it.myPeerID,
 *   it.telemetry, it.fragmentManager, it.transferProgressManager), setOf(wifiAwareBearer))`.
 *   On Apple: `MeshBearers(createNativeBleBearer(it.myPeerID, it.telemetry, it.fragmentManager,
 *   it.transferProgressManager))`.
 * @property meshGraphService route-graph service; defaults to a fresh instance.
 * @property dispatchers coroutine dispatchers; defaults to a fresh [AppDispatchers].
 */
class MeshTransportConfig(
    val encryption: EncryptionService,
    val fingerprints: PeerFingerprintManager,
    val callbacks: MeshCallbacks,
    val stores: MeshStores,
    val bearers: (MeshBearerScope) -> MeshBearers,
    val meshGraphService: MeshGraphService = MeshGraphService(),
    val dispatchers: AppDispatchers = AppDispatchers(),
)

/**
 * DI-agnostic entry point for the mesh stack. [create] performs the wiring the app graph did by
 * hand: it derives the mesh peer id, builds the shared FragmentManager/TransferProgressManager,
 * asks the consumer to construct its platform bearers over those collaborators, assembles the
 * MeshNetwork, and finally the MeshCoordinator.
 *
 * The result is the mesh service and its lifecycle controller (the same coordinator instance).
 */
class MeshTransport private constructor(
    private val coordinator: MeshCoordinator,
    val fragmentManager: FragmentManager,
    val meshNetwork: MeshNetwork,
) {
    /** The mesh data-path port consumed by the routing layer. */
    val service: MeshService get() = coordinator

    /** Narrow lifecycle contract for the platform lifecycle owner (Android FGS / iOS app). */
    val lifecycle: MeshLifecycleController get() = coordinator

    companion object {
        fun create(config: MeshTransportConfig): MeshTransport {
            val myPeerID = config.encryption.getIdentityFingerprint().take(16)
            val fragmentManager = FragmentManager()
            val transferProgressManager = TransferProgressManager(config.dispatchers)
            val built = config.bearers(
                MeshBearerScope(
                    myPeerID = myPeerID,
                    telemetry = config.stores.telemetry,
                    fragmentManager = fragmentManager,
                    transferProgressManager = transferProgressManager,
                ),
            )
            val meshNetwork = MeshNetwork(setOf(built.primary) + built.additional)
            val coordinator = MeshCoordinator(
                incomingFileStore = config.stores.incomingFileStore,
                debugSettingsManager = config.stores.telemetry,
                debugPreferenceManager = config.stores.debugPreferenceManager,
                seenMessageStore = config.stores.seenMessageStore,
                meshGraphService = config.meshGraphService,
                peerFingerprintManager = config.fingerprints,
                encryptionService = config.encryption,
                serviceNotifier = config.callbacks.serviceNotifier,
                nicknameSource = config.callbacks.nicknameSource,
                incomingSink = config.callbacks.incomingSink,
                favoriteNostrLink = config.callbacks.favoriteNostrLink,
                geohashReadReceiptRouter = config.callbacks.readReceiptRouter,
                verificationService = config.callbacks.verificationService,
                fragmentManager = fragmentManager,
                bleBearer = built.primary,
                meshNetwork = meshNetwork,
                dispatchers = config.dispatchers,
            )
            return MeshTransport(coordinator, fragmentManager, meshNetwork)
        }
    }
}
