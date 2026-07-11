@file:OptIn(ExperimentalTime::class)

package com.app.data.courier

import com.app.common.AppDispatchers
import com.app.crypto.EncryptionService
import com.app.data.favorites.FavoritesPersistenceService
import com.app.database.dao.CourierTier
import com.app.transport.courier.CourierEventListener
import com.app.transport.mesh.MeshService
import com.app.transport.model.CourierEnvelope
import com.app.transport.model.NoisePayload
import com.app.transport.model.NoisePayloadType
import com.app.transport.model.PrivateMessagePacket
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
 * Courier store-and-forward coordinator (BitchatPacket 0x04), ported from the reference iOS BLEService
 * courier paths + `courierDepositPolicy`. Platform-free (commonMain), mirroring [VouchCoordinator]
 * [com.app.data.vouch.VouchCoordinator]: attaches itself as [MeshService.courierEventListener] and
 * owns the courier trust policy and orchestration, while the mesh opens envelopes addressed to us and
 * [CourierStore]/`CourierDao` own the carried-mail store.
 *
 * Three roles:
 * - **Courier (deposit):** [onCourierDeposit] — a trusted peer hands us mail for an offline third
 *   party. Authenticate the depositor (packet signature + favorite/verified policy) and store it.
 * - **Courier (handover):** [onCourierPeerAvailable] — a verified announce tells us where a peer is;
 *   hand over carried mail addressed to them (direct = destructive + spray, relayed = speculative
 *   flood).
 * - **Sender (deposit):** [depositForRecipient] — seal our own undelivered message and hand it to
 *   connected couriers (called by the route selector when the recipient is unreachable).
 *
 * There is no courier capability bit in the reference; trust is derived from favorite/verified status.
 */
@SingleIn(AppScope::class)
@Inject
class CourierCoordinator(
    private val meshService: MeshService,
    private val encryption: EncryptionService,
    private val favoritesService: FavoritesPersistenceService,
    private val courierStore: CourierStore,
    dispatchers: AppDispatchers,
) : CourierEventListener {

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())

    init {
        meshService.courierEventListener = this
    }

    // MARK: - Courier role: accept a deposit

    override fun onCourierDeposit(fromPeerID: String, packet: BitchatPacket) {
        scope.launch {
            val info = meshService.getPeerInfo(fromPeerID) ?: return@launch
            val depositorKey = info.noisePublicKey ?: return@launch
            val signingKey = info.signingPublicKey ?: return@launch
            // A deposit must be signed by the claimed sender: the Ed25519 packet signature verifies
            // against the depositor's announce-bound signing key, so mail can only be charged to a
            // quota by the peer who actually owns that identity (no routing an envelope through a
            // trusted neighbor under their quota).
            val signature = packet.signature ?: return@launch
            val signable = packet.toBinaryDataForSigning() ?: return@launch
            if (!encryption.verifyEd25519Signature(signature, signable, signingKey)) return@launch

            val tier = depositTier(depositorKey, info.isVerifiedNickname) ?: return@launch
            val envelope = CourierEnvelope.decode(packet.payload) ?: return@launch
            courierStore.deposit(envelope, depositorKey, tier)
        }
    }

    // MARK: - Courier role: hand over on encountering a peer

    override fun onCourierPeerAvailable(peerID: String) {
        scope.launch {
            if (courierStore.isEmpty()) return@launch
            val info = meshService.getPeerInfo(peerID) ?: return@launch
            if (!info.isVerifiedNickname) return@launch
            val noiseKey = info.noisePublicKey ?: return@launch

            if (info.isDirectConnection) {
                // Established link: destructive handover to the recipient is safe (the depositor's
                // outbox still retains the original), and the peer is close enough to carry other mail.
                for (envelope in courierStore.takeEnvelopes(noiseKey)) {
                    envelope.encode()?.let { meshService.sendCourierEnvelope(it, peerID) }
                }
                // Spray other carried mail to this peer if they pass the same trust gate we would.
                if (depositTier(noiseKey, info.isVerifiedNickname) != null) {
                    for (envelope in courierStore.takeSprayCopies(noiseKey)) {
                        envelope.encode()?.let { meshService.sendCourierEnvelope(it, peerID) }
                    }
                }
            } else {
                // Relayed announce: push a speculative copy toward a multi-hop recipient; the carried
                // copy stays put (non-destructive, per-envelope cooldown in the store).
                for (envelope in courierStore.envelopesForRemoteHandover(noiseKey)) {
                    envelope.encode()?.let { meshService.sendCourierEnvelope(it, peerID) }
                }
            }
        }
    }

    // MARK: - Sender role: deposit our own undelivered message with couriers

    /**
     * Seal [content] for [recipientNoiseKey] and hand the envelope to each connected courier in
     * [courierPeerIDs] for physical delivery. Returns false when there are no couriers or sealing
     * fails. Last-resort path — the caller keeps the message queued so direct delivery still wins if
     * the recipient reappears (receivers dedup by message ID).
     */
    suspend fun depositForRecipient(
        messageID: String,
        content: String,
        recipientNoiseKey: ByteArray,
        courierPeerIDs: List<String>,
    ): Boolean {
        if (courierPeerIDs.isEmpty()) return false
        val pmBytes = PrivateMessagePacket(messageID, content).encode() ?: return false
        val typedPayload = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, pmBytes).encode()
        val sealed = encryption.sealCourierPayload(typedPayload, recipientNoiseKey) ?: return false

        val now = nowMs()
        val envelope = CourierEnvelope(
            recipientTag = CourierEnvelope.recipientTag(recipientNoiseKey, CourierEnvelope.epochDay(now / 1000)),
            expiry = (now + CourierEnvelope.MAX_LIFETIME_SECONDS * 1000).toULong(),
            ciphertext = sealed,
            copies = INITIAL_COPIES,
        )
        val payload = envelope.encode() ?: return false
        for (courier in courierPeerIDs) {
            meshService.sendCourierEnvelope(payload, courier)
        }
        return true
    }

    /**
     * The reference `courierDepositPolicy`: a mutual favorite is the preferred (favorite) tier; a
     * signature-verified non-favorite is the fallback (verified) tier; anyone else is rejected.
     */
    private fun depositTier(noiseKey: ByteArray, isVerified: Boolean): CourierTier? {
        if (favoritesService.getFavoriteStatus(noiseKey)?.isMutual == true) return CourierTier.FAVORITE
        return if (isVerified) CourierTier.VERIFIED else null
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        /** Reference TransportConfig.courierInitialCopies = 4 (spray-and-wait budget). */
        val INITIAL_COPIES: UByte = 4u
    }
}
