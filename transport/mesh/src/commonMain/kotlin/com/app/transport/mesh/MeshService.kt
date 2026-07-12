package com.app.transport.mesh

import com.app.transport.board.BoardEventListener
import com.app.transport.courier.CourierEventListener
import com.app.transport.group.GroupEventListener
import com.app.transport.model.BitchatFilePacket
import com.app.transport.prekey.PrekeyEventListener
import com.app.transport.verification.VerifyEventListener
import com.app.transport.vouch.VouchEventListener

/**
 * commonMain contract for the mesh operations the data layer drives (peer directory, Noise session
 * control, message/file sending, debug surface). Implemented by the androidMain [BluetoothMeshService];
 * lets the routing / repository implementations in :core:data stay platform-free (DIP) without a
 * native mesh yet — the iOS actual is the deferred transport-native follow-up.
 *
 * Narrow lifecycle ([MeshLifecycleController]) and BLE-debug ([BleDebugHandle]) surfaces stay separate
 * (their own consumers); this is the broad app/data-facing facade.
 */
interface MeshService {

    /** Our current mesh ephemeral peer id (16-hex); rotates on panic reset. */
    val myPeerID: String

    /** BLE-specific debug surface for the debug settings sheet. */
    val bleDebug: BleDebugHandle

    fun getPeerInfo(peerID: String): PeerInfo?

    fun getPeerNicknames(): Map<String, String>

    fun hasEstablishedSession(peerID: String): Boolean

    fun initiateNoiseHandshake(peerID: String)

    fun sendAnnouncementToPeer(peerID: String)

    fun sendMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null)

    fun sendPrivateMessage(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String? = null,
    )

    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String)

    fun sendBroadcastAnnounce()

    fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket)

    fun sendFileBroadcast(file: BitchatFilePacket)

    fun cancelFileTransfer(transferId: String): Boolean

    fun getDebugStatus(): String

    /**
     * Sends a directed echo probe (ping 0x26) to [peerID] and suspends until the pong (0x27) comes
     * back, or null on timeout. Diagnostic only — nothing in the stack pings automatically.
     */
    suspend fun pingPeer(peerID: String): MeshPingResult?

    // --- Noise QR-verification surface (driven by the platform-free VerificationCoordinator) ---

    /** The verify-event sink; the coordinator attaches itself so inbound challenges/responses land. */
    var verifyEventListener: VerifyEventListener?

    fun getPeerFingerprint(peerID: String): String?

    fun getStaticNoisePublicKey(): ByteArray?

    fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray)

    fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray)

    // --- Transitive verification (vouch, Noise payload 0x12) ---

    /** The vouch-event sink; the platform-free vouch coordinator attaches itself here. */
    var vouchEventListener: VouchEventListener?

    /** Peer IDs currently connected, used to run a vouch pass when the user verifies someone. */
    fun connectedPeerIDs(): List<String>

    /** Sends an encoded `VouchAttestation` batch body to [peerID] over its Noise session. */
    fun sendVouchAttestations(batchPayload: ByteArray, peerID: String)

    // --- Courier store-and-forward (BitchatPacket 0x04) ---

    /** The courier-event sink; the platform-free courier coordinator attaches itself here. */
    var courierEventListener: CourierEventListener?

    /**
     * Sends an encoded [CourierEnvelope][com.app.transport.model.CourierEnvelope] as a signed, directed
     * 0x04 packet to [toPeerID] — a deposit to a courier, a handover to the recipient, a spray copy to
     * another courier, or a speculative multi-hop flood toward a relayed recipient.
     */
    fun sendCourierEnvelope(payload: ByteArray, toPeerID: String)

    // --- Private groups (0x25 broadcast; 0x06 / 0x07 state over Noise) ---

    /** The group-event sink; the platform-free group coordinator attaches itself here. */
    var groupEventListener: GroupEventListener?

    /**
     * Broadcasts an encoded [GroupMessageEnvelope][com.app.transport.model.GroupMessageEnvelope] as an
     * unsigned 0x25 packet (like a public message): receivers authenticate the sender via the Ed25519
     * signature inside the ciphertext, which still verifies for gossip-backfilled copies.
     */
    fun broadcastGroupMessage(payload: ByteArray)

    /**
     * Sends encoded creator-signed group state 1:1 over [toPeerID]'s Noise session; [isInvite] selects
     * the `GROUP_INVITE (0x06)` vs `GROUP_KEY_UPDATE (0x07)` payload type.
     */
    fun sendGroupState(payload: ByteArray, toPeerID: String, isInvite: Boolean)

    // --- Geohash boards (0x23 broadcast) ---

    /** The board-event sink; the platform-free board coordinator attaches itself here. */
    var boardEventListener: BoardEventListener?

    /**
     * Broadcasts an encoded `BoardWire` payload (post or tombstone) as a signed 0x23 packet. The
     * payload is self-authenticating (inner author Ed25519 signature); the outer packet signature is a
     * nominal first-hop marker (receivers gate on the inner signature).
     */
    fun sendBoardPayload(payload: ByteArray)

    // --- One-time prekey bundles (0x24 broadcast) ---

    /** The prekey-event sink; the platform-free prekey coordinator attaches itself here. */
    var prekeyEventListener: PrekeyEventListener?

    /**
     * Broadcasts an encoded [PrekeyBundle][com.app.transport.model.PrekeyBundle] as a signed 0x24
     * packet. Signed so receivers can verify the outer packet against the owner's announce-bound
     * signing key (defeating replay under a spoofed sender); the inner bundle signature is the
     * primary authenticity gate and survives multi-hop relay + rebroadcast.
     */
    fun sendPrekeyBundle(payload: ByteArray)

    /** Sends an unsigned directed 0x28 carrier; the enclosed Nostr event is self-authenticating. */
    fun sendNostrCarrier(payload: ByteArray, toPeerID: String): Boolean = false

    fun isGatewayEnabled(): Boolean = false

    fun setGatewayEnabled(enabled: Boolean) = Unit

    fun setNostrCarrierHandler(handler: ((payload: ByteArray, fromPeerId: String, directedToUs: Boolean) -> Unit)?) = Unit

    fun broadcastNostrCarrier(payload: ByteArray) = Unit
}
