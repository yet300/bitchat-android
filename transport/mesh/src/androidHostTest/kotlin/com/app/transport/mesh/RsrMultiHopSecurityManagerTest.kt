package com.app.transport.mesh

import android.os.Build
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.secure.SecureKeyValueStore
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.peerIdToRoutingBytes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0.2: multi-hop RSR through SecurityManager —
 * solicitation against hop B, logical author C as peerID (signature path).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class RsrMultiHopSecurityManagerTest {

    private val myPeer = "0a0b0c0d0e0f1011"
    private val hopPeer = "bbbbbbbbbbbbbbbb"
    private val authorPeer = "aaaaaaaaaaaaaaaa"

    private object NoopStore : SecureKeyValueStore {
        override fun getString(key: String): String? = null
        override fun putString(key: String, value: String) {}
        override fun getStringSet(key: String): Set<String>? = null
        override fun putStringSet(key: String, values: Set<String>) {}
        override fun contains(key: String): Boolean = false
        override fun remove(vararg keys: String) {}
        override suspend fun clear() {}
    }

    private class FakeEncryption : EncryptionService(NoopStore, PeerFingerprintManager()) {
        override fun initialize() {}
    }

    private var securityManager: SecurityManager? = null

    @After
    fun tearDown() {
        securityManager?.shutdown()
        securityManager = null
    }

    /** LEAVE: no mandatory outer signature — isolates RSR solicitation from Ed25519. */
    private fun oldRsrLeave() = BitchatPacket(
        version = 1u,
        type = MessageType.LEAVE.value,
        senderID = peerIdToRoutingBytes(authorPeer),
        recipientID = null,
        timestamp = 1_000_000uL,
        payload = byteArrayOf(),
        signature = null,
        ttl = 0u,
        isRSR = true,
    )

    @Test
    fun solicitedHop_authorAsPeerID_acceptsLeaveRsr() {
        val sm = SecurityManager(
            encryptionService = FakeEncryption(),
            myPeerID = myPeer,
            nowMillis = { 1_700_000_000_000L },
            isValidSyncResponse = { it == hopPeer },
        ).also { securityManager = it }

        assertEquals(
            PacketValidationResult.ACCEPT,
            sm.validatePacket(oldRsrLeave(), peerID = authorPeer, previousHopPeerID = hopPeer),
        )
    }

    @Test
    fun missingHop_solicitationFails_evenWithAuthorPeerID() {
        val sm = SecurityManager(
            encryptionService = FakeEncryption(),
            myPeerID = myPeer,
            nowMillis = { 1_700_000_000_000L },
            isValidSyncResponse = { it == hopPeer },
        ).also { securityManager = it }

        assertEquals(
            PacketValidationResult.DROP,
            sm.validatePacket(oldRsrLeave(), peerID = authorPeer, previousHopPeerID = null),
        )
    }

    @Test
    fun wrongHop_dropped() {
        val sm = SecurityManager(
            encryptionService = FakeEncryption(),
            myPeerID = myPeer,
            nowMillis = { 1_700_000_000_000L },
            isValidSyncResponse = { it == hopPeer },
        ).also { securityManager = it }

        assertEquals(
            PacketValidationResult.DROP,
            sm.validatePacket(
                oldRsrLeave(),
                peerID = authorPeer,
                previousHopPeerID = "cccccccccccccccc",
            ),
        )
    }

    /** Self-authored LEAVE as solicited RSR (ttl=0) — iOS selfAuthoredSyncReplayIsAccepted. */
    private fun selfAuthoredRsrLeave() = BitchatPacket(
        version = 1u,
        type = MessageType.LEAVE.value,
        senderID = peerIdToRoutingBytes(myPeer),
        recipientID = null,
        timestamp = 1_000_000uL,
        payload = byteArrayOf(),
        signature = null,
        ttl = 0u,
        isRSR = true,
    )

    @Test
    fun selfAuthoredSolicitedRsr_accepted() {
        val sm = SecurityManager(
            encryptionService = FakeEncryption(),
            myPeerID = myPeer,
            nowMillis = { 1_700_000_000_000L },
            isValidSyncResponse = { it == hopPeer },
        ).also { securityManager = it }

        assertEquals(
            PacketValidationResult.ACCEPT,
            sm.validatePacket(
                selfAuthoredRsrLeave(),
                peerID = myPeer,
                previousHopPeerID = hopPeer,
            ),
        )
    }

    @Test
    fun selfAuthoredWithoutRsr_droppedAsLoopback() {
        val sm = SecurityManager(
            encryptionService = FakeEncryption(),
            myPeerID = myPeer,
            nowMillis = { 1_700_000_000_000L },
            isValidSyncResponse = { it == hopPeer },
        ).also { securityManager = it }

        val echo = selfAuthoredRsrLeave().copy(isRSR = false, ttl = 3u)
        assertEquals(
            PacketValidationResult.DROP,
            sm.validatePacket(echo, peerID = myPeer, previousHopPeerID = hopPeer),
        )
    }

    @Test
    fun selfAuthoredRsr_unsolicitedHop_dropped() {
        val sm = SecurityManager(
            encryptionService = FakeEncryption(),
            myPeerID = myPeer,
            nowMillis = { 1_700_000_000_000L },
            isValidSyncResponse = { it == hopPeer },
        ).also { securityManager = it }

        assertEquals(
            PacketValidationResult.DROP,
            sm.validatePacket(
                selfAuthoredRsrLeave(),
                peerID = myPeer,
                previousHopPeerID = null,
            ),
        )
    }

    @Test
    fun selfAuthoredMessageRsr_unsignedIsDropped() {
        // MESSAGE requires Ed25519 with our signing key; missing signature must DROP
        // even when solicitation is valid (must not skip crypto for self-RSR).
        val sm = SecurityManager(
            encryptionService = FakeEncryption(),
            myPeerID = myPeer,
            nowMillis = { 1_700_000_000_000L },
            isValidSyncResponse = { it == hopPeer },
        ).also { securityManager = it }

        val unsigned = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = peerIdToRoutingBytes(myPeer),
            recipientID = null,
            timestamp = 1_000_000uL,
            payload = "hi".encodeToByteArray(),
            signature = null,
            ttl = 0u,
            isRSR = true,
        )
        assertEquals(
            PacketValidationResult.DROP,
            sm.validatePacket(unsigned, peerID = myPeer, previousHopPeerID = hopPeer),
        )
    }

    @Test
    fun selfAuthoredMessageRsr_signedWithOwnKey_accepted() {
        // Real Ed25519 keys (do not override initialize) so getSigningPublicKey/signData work.
        val enc = EncryptionService(NoopStore, PeerFingerprintManager())
        val localPeer = enc.getIdentityFingerprint().take(16)
        val sm = SecurityManager(
            encryptionService = enc,
            myPeerID = localPeer,
            nowMillis = { 1_700_000_000_000L },
            isValidSyncResponse = { it == hopPeer },
        ).also { securityManager = it }

        val unsigned = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = peerIdToRoutingBytes(localPeer),
            recipientID = null,
            timestamp = 1_000_000uL,
            payload = "self-rsr-history".encodeToByteArray(),
            signature = null,
            ttl = 0u,
            isRSR = true,
        )
        val toSign = requireNotNull(unsigned.toBinaryDataForSigning())
        val signature = requireNotNull(enc.signData(toSign))
        val signed = unsigned.copy(signature = signature)

        assertEquals(
            PacketValidationResult.ACCEPT,
            sm.validatePacket(signed, peerID = localPeer, previousHopPeerID = hopPeer),
        )
    }
}
