@file:OptIn(ExperimentalTime::class)

package com.app.data.prekey

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.common.AppDispatchers
import com.app.common.utils.Log
import com.app.crypto.EncryptionService
import com.app.database.dao.PrekeyBundleDao
import com.app.transport.mesh.MeshService
import com.app.transport.model.PrekeyBundle
import com.app.transport.prekey.PrekeyEventListener
import com.app.transport.protocol.BitchatPacket
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Owns one-time prekey bundles (BitchatPacket 0x24), ported from the reference iOS BLEService prekey
 * paths. Platform-free (commonMain), mirroring [com.app.data.board.BoardCoordinator]: attaches itself
 * as [MeshService.prekeyEventListener], publishes our own signed bundle, and verifies + caches peers'
 * bundles into [PrekeyBundleDao] for later forward-secret courier sealing. The one-time private keys
 * themselves live behind the secure store inside [EncryptionService]; this coordinator never sees them.
 *
 * Publish policy (reference): a bundle is (re)broadcast on startup and after a consumption shrinks it;
 * unforced sends are throttled to [REBROADCAST_MS] so gossip flooding does the spreading while the
 * broadcast just keeps our own entry fresh.
 *
 * Note on gossip: unlike the reference, this port has no per-type GCS sync round, so bundles propagate
 * by broadcast + relay (identical to how board posts spread here) rather than by REQUEST_SYNC diff.
 */
@SingleIn(AppScope::class)
@Inject
class PrekeyCoordinator(
    private val meshService: MeshService,
    private val encryption: EncryptionService,
    private val prekeyBundleDao: PrekeyBundleDao,
    dispatchers: AppDispatchers,
) : PrekeyEventListener {

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())

    private val publishLock = Lock()
    private var lastPublishAtMs = 0L

    init {
        meshService.prekeyEventListener = this
        // Publish our bundle at startup so peers can seal forward-secret mail to us.
        scope.launch { publishBundle(force = true) }
    }

    // MARK: - Inbound

    override fun onPrekeyBundleReceived(packet: BitchatPacket) {
        scope.launch {
            val bundle = PrekeyBundle.decode(packet.payload) ?: return@launch
            // Our own bundle echoing back adds nothing.
            if (encryption.getStaticPublicKey()?.contentEquals(bundle.noiseStaticPublicKey) == true) return@launch

            val signingKey = announceBoundSigningKey(bundle.noiseStaticPublicKey)
            if (signingKey == null) {
                // No verified announce has bound this owner's signing key yet. Drop; the owner
                // re-broadcasts periodically, so a later copy is verified once the announce lands.
                Log.d(TAG, "🔑 Deferring prekey bundle without a bound signing key")
                return@launch
            }
            // Both the inner bundle signature AND the outer packet signature must verify against the
            // owner's announce-bound signing key. Verifying the outer packet — whose signed bytes
            // cover senderID and timestamp — stops a valid bundle being replayed under a spoofed
            // sender or a fresh timestamp to poison attribution.
            val innerOk = bundle.verifySignature(signingKey) { key, data, sig ->
                encryption.verifyEd25519Signature(sig, data, key)
            }
            val signature = packet.signature
            val signable = packet.toBinaryDataForSigning()
            val outerOk = signature != null && signable != null &&
                encryption.verifyEd25519Signature(signature, signable, signingKey)
            if (!innerOk || !outerOk) {
                Log.d(TAG, "🔑 Ignoring prekey bundle without verifiable signature")
                return@launch
            }

            val prekeys = bundle.prekeys.map { it.id to it.publicKey }
            if (prekeyBundleDao.ingest(bundle.noiseStaticPublicKey, bundle.generatedAt.toLong(), prekeys, nowMs())) {
                Log.d(TAG, "🔑 Cached prekey bundle (${bundle.prekeys.size} prekeys)")
            }
        }
    }

    override fun onLocalPrekeyConsumed() {
        scope.launch {
            // A consumed prekey shrank our published bundle (its generatedAt already advanced). Top
            // the batch back up if it ran low, then re-publish — force when the batch changed, else
            // let the rebroadcast throttle coalesce bursts.
            val replenished = encryption.replenishPrekeysIfNeeded()
            publishBundle(force = replenished)
        }
    }

    override fun onAnnounceBroadcast() {
        // Throttled re-publish alongside presence; the throttle keeps announces (every ~30s) from
        // flooding the mesh with bundle re-broadcasts.
        scope.launch { publishBundle(force = false) }
    }

    // MARK: - Publish

    /** Builds, signs and broadcasts our current bundle. Unforced sends obey [REBROADCAST_MS]. */
    private fun publishBundle(force: Boolean) {
        val now = nowMs()
        val shouldSend = publishLock.withLock {
            if (!force && now - lastPublishAtMs < REBROADCAST_MS) return
            lastPublishAtMs = now
            true
        }
        if (!shouldSend) return

        val staticKey = encryption.getStaticPublicKey() ?: return
        val (prekeys, generatedAt) = encryption.currentBundlePrekeys()
        if (prekeys.isEmpty()) return
        val bundle = PrekeyBundle.build(
            noiseStaticPublicKey = staticKey,
            prekeys = prekeys.map { PrekeyBundle.Prekey(it.id, it.publicKey) },
            generatedAt = generatedAt,
        ) { encryption.signData(it) } ?: return
        val payload = bundle.encode() ?: return
        meshService.sendPrekeyBundle(payload)
        Log.d(TAG, "🔑 Published prekey bundle (${prekeys.size} prekeys, generatedAt=$generatedAt)")
    }

    // MARK: - Internals

    /**
     * The Ed25519 signing key bound to [noiseKey] by a verified announce: from a peer currently on
     * the mesh (its announce set the key in the registry), else from identities persisted for offline
     * verification.
     */
    private fun announceBoundSigningKey(noiseKey: ByteArray): ByteArray? {
        peerInfoForNoiseKey(noiseKey)?.signingPublicKey?.let { return it }
        return encryption.announcedSigningKeyForNoiseKey(noiseKey)
    }

    private fun peerInfoForNoiseKey(noiseKey: ByteArray) =
        try {
            meshService.connectedPeerIDs()
                .mapNotNull { meshService.getPeerInfo(it) }
                .firstOrNull { it.noisePublicKey?.contentEquals(noiseKey) == true }
        } catch (_: Exception) {
            null
        }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        private const val TAG = "PrekeyCoordinator"
        /** Reference TransportConfig.prekeyBundleRebroadcastSeconds = 1 hour. */
        const val REBROADCAST_MS = 60L * 60 * 1000
    }
}
