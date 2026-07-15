package com.app.transport.mesh

import android.os.Build
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.secure.SecureKeyValueStore
import com.app.transport.model.IdentityAnnouncement
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class SecurityManagerTest {

    private lateinit var securityManager: SecurityManager
    private lateinit var fakeEncryptionService: FakeEncryptionService
    private lateinit var mockDelegate: SecurityManagerDelegate
    
    private val myPeerID = "1111222233334444"
    private val otherPeerID = "aaaabbbbccccdddd"
    private val unknownPeerID = "9999888877776666"

    private val dummyPayload = "Hello World".toByteArray()
    private val validSignature = ByteArray(64) { 1 }
    private val invalidSignature = ByteArray(64) { 0 }
    
    // Key pairs (using dummy bytes for mock verification)
    private val otherSigningKey = ByteArray(32) { 0xA }
    private val otherNoiseKey = ByteArray(32) { 0xB }

    /** No-op secure store: the fake overrides [initialize] so storage is never touched. */
    object NoopSecureKeyValueStore : SecureKeyValueStore {
        override fun getString(key: String): String? = null
        override fun putString(key: String, value: String) {}
        override fun getStringSet(key: String): Set<String>? = null
        override fun putStringSet(key: String, values: Set<String>) {}
        override fun contains(key: String): Boolean = false
        override fun remove(vararg keys: String) {}
        override suspend fun clear() {}
    }

    // Fake implementation to bypass initialization issues in tests
    open class FakeEncryptionService : EncryptionService(NoopSecureKeyValueStore, PeerFingerprintManager()) {
        var shouldVerify: Boolean = true
        var lastVerifySignature: ByteArray? = null
        var lastVerifyKey: ByteArray? = null

        override fun initialize() {
            // Do nothing to avoid KeyStore access in tests
        }

        override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKeyBytes: ByteArray): Boolean {
            lastVerifySignature = signature
            lastVerifyKey = publicKeyBytes
            
            // Simple logic: if configured to verify, check if signature matches validSignature
            // We use the signature bytes passed in setup()
            if (shouldVerify) {
                 return signature.contentEquals(byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
            }
            return false
        }
    }

    /**
     * Frozen "now" aligned with wall clock at test start. Packet fixtures that use the
     * BitchatPacket(senderID: String) convenience ctor stamp System.currentTimeMillis();
     * [nowMillis] must stay within 120s of those stamps for the ingress skew gate.
     */
    private var testNowMs: Long = 0L

    @Before
    fun setup() {
        testNowMs = System.currentTimeMillis()
        fakeEncryptionService = FakeEncryptionService()
        mockDelegate = mock()
        
        securityManager = SecurityManager(
            encryptionService = fakeEncryptionService,
            myPeerID = myPeerID,
            nowMillis = { testNowMs },
        )
        securityManager.delegate = mockDelegate
    }

    @After
    fun tearDown() {
        if (::securityManager.isInitialized) {
            securityManager.shutdown()
        }
    }

    @Test
    fun `validatePacket - rejects packet with missing signature`() {
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = otherPeerID,
            payload = dummyPayload
        )
        packet.signature = null

        val result = securityManager.validatePacket(packet, otherPeerID)
        
        assertEquals("Packet without signature should be rejected", PacketValidationResult.DROP, result)
    }

    @Test
    fun `validatePacket - rejects packet with invalid signature`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = otherPeerID,
            payload = dummyPayload
        )
        packet.signature = invalidSignature

        val result = securityManager.validatePacket(packet, otherPeerID)
        
        assertEquals("Packet with invalid signature should be rejected", PacketValidationResult.DROP, result)
    }

    @Test
    fun `validatePacket - rejects packet from unknown peer (no key)`() {
        whenever(mockDelegate.getPeerInfo(unknownPeerID)).thenReturn(null)
        
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = unknownPeerID,
            payload = dummyPayload
        )
        packet.signature = validSignature

        val result = securityManager.validatePacket(packet, unknownPeerID)
        
        assertEquals("Packet from unknown peer should be rejected (cannot verify signature)", PacketValidationResult.DROP, result)
    }

    @Test
    fun `validatePacket - accepts packet with valid signature from known peer`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = otherPeerID,
            payload = dummyPayload
        )
        packet.signature = validSignature

        val result = securityManager.validatePacket(packet, otherPeerID)
        
        assertEquals("Valid signed packet from known peer should be accepted", PacketValidationResult.ACCEPT, result)
    }

    @Test
    fun `validatePacket - accepts ANNOUNCE packet from unknown peer (extracts key)`() {
        val announcement = IdentityAnnouncement(
            nickname = "New User",
            noisePublicKey = otherNoiseKey,
            signingPublicKey = otherSigningKey
        )
        val payload = announcement.encode()!!
        
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 10u,
            senderID = unknownPeerID,
            payload = payload
        )
        packet.signature = validSignature

        whenever(mockDelegate.getPeerInfo(unknownPeerID)).thenReturn(null)
        
        val result = securityManager.validatePacket(packet, unknownPeerID)
        
        assertEquals("ANNOUNCE from unknown peer should be accepted (key extracted from payload)", PacketValidationResult.ACCEPT, result)
        // Verify we used the correct key
        assertTrue("Should have used extracted key for verification", 
            fakeEncryptionService.lastVerifyKey.contentEquals(otherSigningKey))
    }

    @Test
    fun `validatePacket - rejects ANNOUNCE packet with invalid signature`() {
        val announcement = IdentityAnnouncement(
            nickname = "New User",
            noisePublicKey = otherNoiseKey,
            signingPublicKey = otherSigningKey
        )
        val payload = announcement.encode()!!
        
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 10u,
            senderID = unknownPeerID,
            payload = payload
        )
        packet.signature = invalidSignature

        val result = securityManager.validatePacket(packet, unknownPeerID)
        
        assertEquals("ANNOUNCE with invalid signature should be rejected", PacketValidationResult.DROP, result)
    }
    
    @Test
    fun `validatePacket - rejects ANNOUNCE packet with malformed payload`() {
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 10u,
            senderID = unknownPeerID,
            payload = byteArrayOf(0x00, 0x01, 0x02)
        )
        packet.signature = validSignature

        val result = securityManager.validatePacket(packet, unknownPeerID)
        
        assertEquals("ANNOUNCE with malformed payload should be rejected (cannot extract key)", PacketValidationResult.DROP, result)
    }

    @Test
    fun `validatePacket - ignores own packets`() {
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = myPeerID,
            payload = dummyPayload
        )
        packet.signature = null

        val result = securityManager.validatePacket(packet, myPeerID)
        
        assertEquals("Own packets should be dropped", PacketValidationResult.DROP, result)
    }
    
    @Test
    fun `validatePacket - detects duplicates`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = otherPeerID,
            payload = dummyPayload
        )
        packet.signature = validSignature

        val result1 = securityManager.validatePacket(packet, otherPeerID)
        assertEquals("First packet should be accepted", PacketValidationResult.ACCEPT, result1)

        val result2 = securityManager.validatePacket(packet, otherPeerID)
        assertEquals("Duplicate packet should be marked for relay cancellation", PacketValidationResult.DUPLICATE, result2)
    }

    /**
     * Pins the M1 dedup-key fix: the key is sha256(payload)-based (iOS
     * BLEReceivePipeline parity), so distinct packets sharing sender, timestamp,
     * type and even the first 64 payload bytes must NOT be collapsed as duplicates.
     * The previous key hashed only the first 64 bytes with the forgeable 32-bit
     * contentHashCode and would have dropped the second packet.
     */
    @Test
    fun `validatePacket - distinct payloads with same sender timestamp type and 64-byte prefix are both accepted`() {
        setupKnownPeer(otherPeerID, otherSigningKey)

        val prefix = ByteArray(64) { 7 }
        fun packet(tail: Byte) = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xDD.toByte()),
            recipientID = null,
            timestamp = testNowMs.toULong(),
            payload = prefix + tail,
            signature = validSignature,
            ttl = 7u
        )

        assertEquals("First packet must be accepted", PacketValidationResult.ACCEPT, securityManager.validatePacket(packet(1), otherPeerID))
        assertEquals(
            "Second packet differing only past byte 64 must be accepted",
            PacketValidationResult.ACCEPT,
            securityManager.validatePacket(packet(2), otherPeerID)
        )
    }

    @Test
    fun `validatePacket - identical payload with same sender and timestamp is still deduplicated`() {
        setupKnownPeer(otherPeerID, otherSigningKey)

        fun packet() = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xDD.toByte()),
            recipientID = null,
            timestamp = testNowMs.toULong(),
            payload = dummyPayload,
            signature = validSignature,
            ttl = 7u
        )

        assertEquals(PacketValidationResult.ACCEPT, securityManager.validatePacket(packet(), otherPeerID))
        assertEquals("Byte-identical replay must not be accepted", PacketValidationResult.DUPLICATE, securityManager.validatePacket(packet(), otherPeerID))
    }

    @Test
    fun `validatePacket - handles ANNOUNCE duplicates correctly`() {
        val announcement = IdentityAnnouncement(
            nickname = "New User",
            noisePublicKey = otherNoiseKey,
            signingPublicKey = otherSigningKey
        )
        val payload = announcement.encode()!!
        
        // 1. Initial Announce (Fresh)
        val packet1 = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = com.app.transport.MeshConstants.MESSAGE_TTL_HOPS, // 7u
            senderID = unknownPeerID,
            payload = payload
        )
        packet1.signature = validSignature
        
        whenever(mockDelegate.getPeerInfo(unknownPeerID)).thenReturn(null)

        assertEquals("First ANNOUNCE should be accepted", PacketValidationResult.ACCEPT, securityManager.validatePacket(packet1, unknownPeerID))
        
        // 2. Relayed Duplicate (Lower TTL)
        val packet2 = packet1.copy(ttl = (com.app.transport.MeshConstants.MESSAGE_TTL_HOPS - 1u).toUByte())
        assertEquals("Relayed duplicate ANNOUNCE should cancel a pending relay", PacketValidationResult.DUPLICATE, securityManager.validatePacket(packet2, unknownPeerID))
        
        // 3. Direct Duplicate (Max TTL)
        val packet3 = packet1.copy(ttl = com.app.transport.MeshConstants.MESSAGE_TTL_HOPS)
        assertEquals("Direct duplicate ANNOUNCE must be liveness-only, never a full accept", PacketValidationResult.DUPLICATE_ANNOUNCE_LIVENESS, securityManager.validatePacket(packet3, unknownPeerID))
    }

    /**
     * Pins the storm fix: a byte-identical direct-neighbor ANNOUNCE replayed many times
     * (a peer resending its cached announce via gossip sync) must stay liveness-only on
     * every repeat — it must never escalate back to a full ACCEPT that would re-relay
     * and re-schedule sync.
     */
    @Test
    fun `validatePacket - repeated direct duplicate ANNOUNCE stays liveness-only`() {
        val announcement = IdentityAnnouncement(
            nickname = "New User",
            noisePublicKey = otherNoiseKey,
            signingPublicKey = otherSigningKey
        )
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = com.app.transport.MeshConstants.MESSAGE_TTL_HOPS,
            senderID = unknownPeerID,
            payload = announcement.encode()!!
        )
        packet.signature = validSignature
        whenever(mockDelegate.getPeerInfo(unknownPeerID)).thenReturn(null)

        assertEquals(PacketValidationResult.ACCEPT, securityManager.validatePacket(packet, unknownPeerID))
        repeat(10) {
            assertEquals(
                "Replay #$it must be liveness-only",
                PacketValidationResult.DUPLICATE_ANNOUNCE_LIVENESS,
                securityManager.validatePacket(packet.copy(), unknownPeerID)
            )
        }
    }

    /**
     * The messageID is recorded before signature verification, so a forged announce that
     * failed verification must NOT gain liveness treatment when replayed.
     */
    @Test
    fun `validatePacket - duplicate ANNOUNCE with invalid signature is dropped, not liveness`() {
        val announcement = IdentityAnnouncement(
            nickname = "Forger",
            noisePublicKey = otherNoiseKey,
            signingPublicKey = otherSigningKey
        )
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = com.app.transport.MeshConstants.MESSAGE_TTL_HOPS,
            senderID = unknownPeerID,
            payload = announcement.encode()!!
        )
        packet.signature = invalidSignature
        whenever(mockDelegate.getPeerInfo(unknownPeerID)).thenReturn(null)

        assertEquals(PacketValidationResult.DROP, securityManager.validatePacket(packet, unknownPeerID))
        assertEquals(
            "Replayed forgery must stay dropped",
            PacketValidationResult.DROP,
            securityManager.validatePacket(packet.copy(), unknownPeerID)
        )
    }

    /**
     * S3 fix: the processedMessages cap must hold at every insertion (LRU eviction of the
     * eldest), not only in the 5-minute cleanup pass. Feeding more than the cap of distinct
     * packets in one burst previously let the set grow to the burst size between cleanups.
     */
    @Test
    fun `validatePacket - processed message set is capped at insertion, not only cleanup`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        val cap = com.app.transport.MeshConstants.Security.MAX_PROCESSED_MESSAGES
        val overCap = cap + 50

        repeat(overCap) { i ->
            val packet = BitchatPacket(
                type = MessageType.MESSAGE.value,
                ttl = 7u,
                senderID = ByteArray(8) { 0xAB.toByte() },
                // Distinct timestamps within the 120s ingress skew window of [testNowMs].
                timestamp = (testNowMs + i).toULong(),
                payload = "m$i".encodeToByteArray(),
                signature = validSignature,
            )
            assertEquals(
                "Distinct packet #$i must be accepted",
                PacketValidationResult.ACCEPT,
                securityManager.validatePacket(packet, otherPeerID),
            )
        }

        val processed = Regex("Processed Messages: (\\d+)")
            .find(securityManager.getDebugInfo())!!
            .groupValues[1].toInt()
        assertEquals(
            "Cap must be enforced at insertion — set must not grow to the burst size ($overCap)",
            cap,
            processed,
        )
    }

    private fun setupKnownPeer(peerID: String, signingKey: ByteArray) {
        val info = PeerInfo(
            id = peerID,
            nickname = "Test User",
            isConnected = true,
            isDirectConnection = true,
            noisePublicKey = ByteArray(32),
            signingPublicKey = signingKey,
            isVerifiedNickname = false,
            lastSeen = System.currentTimeMillis()
        )
        whenever(mockDelegate.getPeerInfo(peerID)).thenReturn(info)
    }
}
