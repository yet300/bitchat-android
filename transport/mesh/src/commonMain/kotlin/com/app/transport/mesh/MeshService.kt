package com.app.transport.mesh

import com.app.transport.courier.CourierEventListener
import com.app.transport.model.BitchatFilePacket
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
}
