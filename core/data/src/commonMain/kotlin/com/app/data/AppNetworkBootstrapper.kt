package com.app.data

import com.app.common.utils.Log
import com.app.data.nostr.NostrDirectMessageIngest
import com.app.data.repository.NicknameSync
import com.app.data.tor.TorActivationController
import com.app.data.vouch.VouchCoordinator
import com.app.transport.net.ArtiTorManager
import com.app.transport.nostr.LocationNotesInitializer
import com.app.transport.nostr.LocationNotesManager
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.nostr.NostrRelayManager
import com.app.transport.nostr.RelayDirectory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * One-shot startup of the Nostr/Tor network stack, hoisted out of the Android `Application.onCreate`
 * so both platform entry points run the exact same sequence (invariant: no duplication in Swift).
 *
 * The step order is load-bearing and must not change: Tor first (so any early network egress is
 * already proxied), then the relay-reconnect hook, then the relay directory (without it
 * [RelayDirectory.closestRelaysForGeohash] returns an empty list and geohash channels connect to no
 * relays), then location notes, identity warm-up and finally the incoming DM ingest (which also
 * connects the default relays). Each step keeps the same try/catch resilience the Android path had —
 * a failing network stack must never crash the app — except the relay-directory load, which was
 * deliberately left un-guarded upstream.
 *
 * All calls are non-blocking (they dispatch their own work), matching the historical behaviour of
 * being invoked directly on the platform main thread at launch.
 */
@SingleIn(AppScope::class)
@Inject
class AppNetworkBootstrapper(
    private val nicknameSync: NicknameSync,
    private val artiTorManager: ArtiTorManager,
    private val nostrRelayManager: NostrRelayManager,
    private val relayDirectory: RelayDirectory,
    private val locationNotesManager: LocationNotesManager,
    private val nostrIdentityBridge: NostrIdentityBridge,
    private val nostrDirectMessageIngest: NostrDirectMessageIngest,
    private val torActivationController: TorActivationController,
    // Injected only to force eager creation: its init attaches the mesh vouch-event listener and
    // starts observing the verified set. Without a reference here nothing constructs it (no UI does).
    private val vouchCoordinator: VouchCoordinator,
) {
    private companion object {
        private const val TAG = "AppNetworkBootstrapper"
    }

    /** Runs the launch-time network bootstrap once. Safe to call on the main thread. */
    fun start() {
        // 0) Load the persisted nickname into the transport-side holder (and drop the legacy
        // plaintext copy) before any bearer can send its first announce.
        try {
            nicknameSync.start()
        } catch (_: Exception) {
        }

        // 1) Initialize Tor first so any early network goes over Tor, and 2) reconnect Nostr relays
        // when Tor resets (wired here so ArtiTorManager stays unaware of nostr).
        try {
            artiTorManager.init()
            artiTorManager.onConnectionsReset = { nostrRelayManager.resetAllConnections() }
            // Dynamic activation: derive the effective Tor mode from the user preference AND the
            // policy (location authorized or a mutual favorite), like the reference iOS
            // NetworkActivationService. Writes the effective mode that ArtiTorManager already reacts
            // to, so at first launch relays connect directly (fast) and Tor engages on demand.
            torActivationController.start()
        } catch (_: Exception) {
        }

        // 3) Initialize relay directory (loads the bundled nostr_relays.csv). Intentionally un-guarded.
        relayDirectory.initialize()

        // 4) Initialize LocationNotesManager dependencies early so sheet subscriptions can start.
        try {
            LocationNotesInitializer.initialize(
                relayDirectory,
                nostrRelayManager,
                locationNotesManager,
                nostrIdentityBridge,
            )
        } catch (_: Exception) {
        }

        // 5) Warm up Nostr identity to ensure npub is available for favorite notifications.
        try {
            nostrIdentityBridge.getCurrentNostrIdentity()
        } catch (_: Exception) {
        }

        // 6) Start the incoming Nostr DM ingest (account + per-geohash gift wraps). It also connects
        // the default relays at startup so favorite DMs and receipts flow even with Tor off / no geo.
        try {
            nostrDirectMessageIngest.start()
        } catch (_: Exception) {
        }

        // Touch the vouch coordinator so it is constructed (its init wires the mesh listener). The
        // reference alone forces creation; this keeps the dependency from reading as unused.
        vouchCoordinator.hashCode()

        Log.i(TAG, "Network bootstrap complete")
    }
}
