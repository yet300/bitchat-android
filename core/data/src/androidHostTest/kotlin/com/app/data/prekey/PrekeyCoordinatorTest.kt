@file:OptIn(ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.app.data.prekey

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.app.common.AppDispatchers
import com.app.common.serialization.JsonConfig
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.secure.SecureKeyValueStore
import com.app.database.BitMessageDatabase
import com.app.database.dao.PrekeyBundleDao
import com.app.database.db.DatabaseDriverFactory
import com.app.database.db.DatabaseManager
import com.app.transport.board.BoardEventListener
import com.app.transport.courier.CourierEventListener
import com.app.transport.group.GroupEventListener
import com.app.transport.mesh.BleDebugHandle
import com.app.transport.mesh.MeshPingResult
import com.app.transport.mesh.MeshService
import com.app.transport.mesh.PeerInfo
import com.app.transport.model.BitchatFilePacket
import com.app.transport.model.NoisePayload
import com.app.transport.model.NoisePayloadType
import com.app.transport.model.PrekeyBundle
import com.app.transport.model.PrivateMessagePacket
import com.app.transport.prekey.PrekeyEventListener
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import com.app.transport.protocol.peerIdToRoutingBytes
import com.app.transport.verification.VerifyEventListener
import com.app.transport.vouch.VouchEventListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two-node prekey acceptance over a fake mesh with real Ed25519 + Curve25519:
 * A publishes a signed bundle → B verifies + caches it → B seals a courier envelope v2 to one of
 * A's prekeys → A opens it, authenticating B and consuming the prekey; a redelivery of the same
 * ciphertext re-opens without a second retirement, and an unknown prekey id is refused.
 *
 * Also covers the ingest gates: a bundle whose owner signing key is unknown, or whose inner or outer
 * signature is forged, is not cached.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrekeyCoordinatorTest {

    private val captured = ArrayList<ByteArray>()

    private fun TestScope.newNode() =
        Node(AppDispatchers(io = UnconfinedTestDispatcher(testScheduler)))

    private inner class Node(val dispatchers: AppDispatchers) {
        val encryption = EncryptionService(InMemoryStore(), PeerFingerprintManager())
        val dao = PrekeyBundleDao(newManager(dispatchers), dispatchers)
        val mesh = FakePrekeyMesh()
        val coordinator = PrekeyCoordinator(mesh, encryption, dao, dispatchers)

        val noiseKey: ByteArray get() = encryption.getStaticPublicKey()!!
        val signingKey: ByteArray get() = encryption.getSigningPublicKey()!!

        /** The bundle payload this node published at startup (captured by its fake mesh). */
        fun publishedBundle(): ByteArray = mesh.published.last()

        /**
         * Wraps [payload] as a 0x24 packet signed by THIS node, exactly as MeshOutboundSender would.
         * The senderID is not load-bearing (the coordinator authenticates via the signatures, not the
         * claimed sender), so a fixed value is used.
         */
        fun signedPacket(payload: ByteArray): BitchatPacket {
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.PREKEY_BUNDLE.value,
                senderID = peerIdToRoutingBytes("00112233aabbccdd"),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = 1_700_000_000_000uL,
                payload = payload,
                ttl = 7u,
            )
            packet.signature = encryption.signData(packet.toBinaryDataForSigning()!!)
            return packet
        }
    }

    @AfterTest
    fun tearDown() = captured.clear()

    @Test
    fun a_publishes_b_seals_v2_a_opens_and_consumes() = runTest {
        val alice = newNode()
        val bob = newNode()
        // Bob knows Alice's announce-bound signing key (as if a verified announce had landed).
        bob.encryption.cacheAnnouncedSigningKey(alice.noiseKey, alice.signingKey)

        // Alice's startup publish is captured; Bob ingests the signed packet.
        val packet = alice.signedPacket(alice.publishedBundle())
        bob.coordinator.onPrekeyBundleReceived(packet)

        // Bob now has a usable bundle and assigns a prekey to seal message "m1".
        assertTrue(bob.dao.hasUsableBundle(alice.noiseKey, NOW))
        val prekey = bob.dao.assignPrekey("m1", alice.noiseKey, NOW)
        assertNotNull(prekey)

        val typed = NoisePayload(
            NoisePayloadType.PRIVATE_MESSAGE,
            PrivateMessagePacket("m1", "forward secret hello").encode()!!,
        ).encode()
        val sealed = bob.encryption.sealPrekeyPayload(typed, prekey!!.id, prekey.publicKey)
        assertNotNull(sealed)

        // Alice opens the v2 envelope: recovers the payload, authenticates Bob, retires the prekey.
        val opened = alice.encryption.openPrekeyPayload(sealed!!, prekey.id)
        assertNotNull(opened)
        assertContentEquals(typed, opened!!.payload)
        assertContentEquals(bob.noiseKey, opened.senderStaticKey)
        assertTrue(opened.consumedPrekey)

        // A duplicate courier redelivery re-opens within grace but reports no fresh retirement.
        val redelivered = alice.encryption.openPrekeyPayload(sealed, prekey.id)
        assertNotNull(redelivered)
        assertFalse(redelivered!!.consumedPrekey)

        // An unknown prekey id is refused.
        assertNull(alice.encryption.openPrekeyPayload(sealed, prekeyID = 9999u))
    }

    @Test
    fun publish_produces_a_valid_self_signed_bundle() = runTest {
        val alice = newNode()
        val bundle = PrekeyBundle.decode(alice.publishedBundle())
        assertNotNull(bundle)
        assertContentEquals(alice.noiseKey, bundle!!.noiseStaticPublicKey)
        assertEquals(8, bundle.prekeys.size)
        assertTrue(
            bundle.verifySignature(alice.signingKey) { k, d, s ->
                alice.encryption.verifyEd25519Signature(s, d, k)
            },
        )
    }

    @Test
    fun bundle_from_unknown_owner_is_not_cached() = runTest {
        val alice = newNode()
        val bob = newNode()
        // Bob was never told Alice's signing key: the ingest must drop it.
        bob.coordinator.onPrekeyBundleReceived(alice.signedPacket(alice.publishedBundle()))
        assertFalse(bob.dao.hasUsableBundle(alice.noiseKey, NOW))
    }

    @Test
    fun bundle_with_forged_outer_signature_is_not_cached() = runTest {
        val alice = newNode()
        val bob = newNode()
        val mallory = newNode()
        bob.encryption.cacheAnnouncedSigningKey(alice.noiseKey, alice.signingKey)

        // Alice's real (inner-signed) bundle, but the outer packet is signed by Mallory.
        val packet = mallory.signedPacket(alice.publishedBundle())
        bob.coordinator.onPrekeyBundleReceived(packet)
        assertFalse(bob.dao.hasUsableBundle(alice.noiseKey, NOW), "outer signature must verify as the owner")
    }

    @Test
    fun bundle_not_matching_the_bound_signing_key_is_not_cached() = runTest {
        val alice = newNode()
        val bob = newNode()
        val mallory = newNode()
        // Bob is told Alice's noise key maps to MALLORY's signing key (a poisoned binding). Alice's
        // real bundle + packet then verify against neither the inner nor the outer expectation, so
        // the bundle is dropped.
        bob.encryption.cacheAnnouncedSigningKey(alice.noiseKey, mallory.signingKey)
        bob.coordinator.onPrekeyBundleReceived(alice.signedPacket(alice.publishedBundle()))
        assertFalse(bob.dao.hasUsableBundle(alice.noiseKey, NOW))
    }

    // ---- fakes ----

    private fun newManager(dispatchers: AppDispatchers): DatabaseManager {
        val factory = object : DatabaseDriverFactory {
            private val driver: SqlDriver =
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BitMessageDatabase.Schema)
            override suspend fun create(): SqlDriver = driver
        }
        return DatabaseManager(factory, dispatchers)
    }

    private class InMemoryStore : SecureKeyValueStore {
        private val map = LinkedHashMap<String, String>()
        @Synchronized override fun getString(key: String): String? = map[key]
        @Synchronized override fun putString(key: String, value: String) { map[key] = value }
        override fun getStringSet(key: String): Set<String>? = getString(key)?.let {
            runCatching { JsonConfig.json.decodeFromString(SetSerializer(String.serializer()), it) }.getOrNull()
        }
        override fun putStringSet(key: String, values: Set<String>) =
            putString(key, JsonConfig.json.encodeToString(SetSerializer(String.serializer()), values))
        @Synchronized override fun contains(key: String): Boolean = map.containsKey(key)
        @Synchronized override fun remove(vararg keys: String) { keys.forEach(map::remove) }
        override suspend fun clear() = synchronized(map) { map.clear() }
    }

    private class FakePrekeyMesh : MeshService {
        val published = ArrayList<ByteArray>()
        override var prekeyEventListener: PrekeyEventListener? = null
        override fun sendPrekeyBundle(payload: ByteArray) { published.add(payload) }

        override var vouchEventListener: VouchEventListener? = null
        override var verifyEventListener: VerifyEventListener? = null
        override var courierEventListener: CourierEventListener? = null
        override var groupEventListener: GroupEventListener? = null
        override var boardEventListener: BoardEventListener? = null
        override fun sendBoardPayload(payload: ByteArray) = Unit
        override fun broadcastGroupMessage(payload: ByteArray) = Unit
        override fun sendGroupState(payload: ByteArray, toPeerID: String, isInvite: Boolean) = Unit
        override fun sendVouchAttestations(batchPayload: ByteArray, peerID: String) = Unit
        override fun sendCourierEnvelope(payload: ByteArray, toPeerID: String) = Unit
        override fun getPeerFingerprint(peerID: String): String? = null
        override fun getPeerInfo(peerID: String): PeerInfo? = null
        override fun getPeerNicknames(): Map<String, String> = emptyMap()
        override fun connectedPeerIDs(): List<String> = emptyList()
        override val myPeerID: String get() = "self"
        override val bleDebug: BleDebugHandle get() = throw NotImplementedError()
        override fun hasEstablishedSession(peerID: String): Boolean = true
        override fun initiateNoiseHandshake(peerID: String) = Unit
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendMessage(content: String, mentions: List<String>, channel: String?) = Unit
        override fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String?) = Unit
        override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) = Unit
        override fun sendBroadcastAnnounce() = Unit
        override fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) = Unit
        override fun sendFileBroadcast(file: BitchatFilePacket) = Unit
        override fun cancelFileTransfer(transferId: String): Boolean = false
        override fun getDebugStatus(): String = ""
        override suspend fun pingPeer(peerID: String): MeshPingResult? = null
        override fun getStaticNoisePublicKey(): ByteArray? = null
        override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) = Unit
        override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) = Unit
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
