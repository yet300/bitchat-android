@file:OptIn(ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.app.data.vouch

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.app.common.AppDispatchers
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.common.serialization.JsonConfig
import com.app.crypto.secure.SecureKeyValueStore
import com.app.database.BitMessageDatabase
import com.app.database.dao.VouchDao
import com.app.database.db.DatabaseDriverFactory
import com.app.database.db.DatabaseManager
import com.app.domain.model.Fingerprint
import com.app.domain.model.MyIdentity
import com.app.domain.repository.IdentityRepository
import com.app.transport.courier.CourierEventListener
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import com.app.transport.mesh.BleDebugHandle
import com.app.transport.mesh.MeshPingResult
import com.app.transport.mesh.MeshService
import com.app.transport.mesh.PeerInfo
import com.app.transport.model.BitchatFilePacket
import com.app.transport.model.PeerCapabilities
import com.app.transport.verification.VerifyEventListener
import com.app.transport.vouch.VouchEventListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end transitive-verification flow across two coordinator instances (Alice and Bob) wired to
 * each other through fake mesh services, with real Ed25519 signing/verification. Alice vouches for
 * Carol to Bob; Bob (who verified Alice) accepts, so Carol becomes vouched-by-Alice on Bob's side.
 */
class VouchCoordinatorTest {

    private val dispatchers = AppDispatchers(io = UnconfinedTestDispatcher())

    private fun newDao(): VouchDao {
        val factory = object : DatabaseDriverFactory {
            private val driver: SqlDriver =
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BitMessageDatabase.Schema)
            override suspend fun create(): SqlDriver = driver
        }
        return VouchDao(DatabaseManager(factory, dispatchers), dispatchers)
    }

    /** A node's identity + crypto + trust state, plus its coordinator. */
    private inner class Node {
        val store = InMemoryStore()
        val encryption = EncryptionService(store, PeerFingerprintManager())
        val identityState = SecureIdentityStateManager(store)
        val dao = newDao()
        val mesh = FakeMeshService()
        val identityRepo = FakeIdentityRepository()
        lateinit var coordinator: VouchCoordinator

        val fingerprint: String get() = encryption.getIdentityFingerprint()
        val signingKey: ByteArray get() = encryption.getSigningPublicKey()!!

        fun build() {
            coordinator = VouchCoordinator(mesh, identityState, encryption, dao, identityRepo, dispatchers)
        }

        /** Mark [other] verified and cache its announce-bound signing key (as a real announce would). */
        fun verify(other: Node, atMs: Long) {
            identityState.setVerifiedFingerprint(other.fingerprint, true, nowMs = atMs)
            identityState.cacheSigningPublicKey(other.fingerprint, other.signingKey)
            identityRepo.verified.value = identityState.getVerifiedFingerprints().map(::Fingerprint).toSet()
        }
    }

    /**
     * Wires Alice→Bob delivery and the peer-id resolutions. Called *after* all `verify()` calls so
     * the eager QR-completion trigger (which fires on each verify) finds no connected peers during
     * setup; `onPeerAuthenticated` is then the single deterministic emission trigger per test.
     */
    private fun connect(
        alice: Node,
        bob: Node,
        capabilities: PeerCapabilities = PeerCapabilities.VOUCH,
        deliver: (ByteArray) -> Unit = { payload -> bob.coordinator.onVouchAttestations(ALICE_PID, payload, 0) },
    ) {
        alice.mesh.deliverVouchTo = deliver
        alice.mesh.fingerprintByPeer[BOB_PID] = bob.fingerprint
        alice.mesh.peerInfo[BOB_PID] = peerInfo(BOB_PID, capabilities)
        bob.mesh.fingerprintByPeer[ALICE_PID] = alice.fingerprint
    }

    @Test
    fun alice_vouches_for_carol_and_bob_accepts() = runTest {
        val alice = Node().apply { build() }
        val bob = Node().apply { build() }
        val carol = Node().apply { build() }

        // Alice verified Bob and Carol (and cached their signing keys); Bob verified Alice.
        alice.verify(bob, atMs = 1_000)
        alice.verify(carol, atMs = 2_000)
        bob.verify(alice, atMs = 1_000)
        connect(alice, bob)

        // Bob's Noise session comes up at Alice: Alice sends the vouch batch for Carol.
        alice.coordinator.onPeerAuthenticated(BOB_PID, bob.fingerprint)

        val vouchers = bob.coordinator.validVouchers(Fingerprint(carol.fingerprint))
        assertEquals(listOf(alice.fingerprint), vouchers.map { it.value })
        assertTrue(bob.coordinator.isVouched(Fingerprint(carol.fingerprint)))
    }

    @Test
    fun bob_rejects_vouches_from_a_peer_he_has_not_verified() = runTest {
        val alice = Node().apply { build() }
        val bob = Node().apply { build() }
        val carol = Node().apply { build() }

        alice.verify(bob, atMs = 1_000)
        alice.verify(carol, atMs = 2_000)
        // Bob does NOT verify Alice.
        connect(alice, bob)

        alice.coordinator.onPeerAuthenticated(BOB_PID, bob.fingerprint)

        assertTrue(bob.coordinator.validVouchers(Fingerprint(carol.fingerprint)).isEmpty())
        assertFalse(bob.coordinator.isVouched(Fingerprint(carol.fingerprint)))
    }

    @Test
    fun a_tampered_signature_is_rejected() = runTest {
        val alice = Node().apply { build() }
        val bob = Node().apply { build() }
        val carol = Node().apply { build() }

        alice.verify(bob, atMs = 1_000)
        alice.verify(carol, atMs = 2_000)
        bob.verify(alice, atMs = 1_000)
        // Deliver a batch whose signature bytes were flipped.
        connect(alice, bob) { payload ->
            val tampered = payload.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
            bob.coordinator.onVouchAttestations(ALICE_PID, tampered, 0)
        }

        alice.coordinator.onPeerAuthenticated(BOB_PID, bob.fingerprint)

        assertTrue(bob.coordinator.validVouchers(Fingerprint(carol.fingerprint)).isEmpty())
    }

    @Test
    fun unverifying_the_voucher_invalidates_the_vouch_without_deleting_it() = runTest {
        val alice = Node().apply { build() }
        val bob = Node().apply { build() }
        val carol = Node().apply { build() }

        alice.verify(bob, atMs = 1_000)
        alice.verify(carol, atMs = 2_000)
        bob.verify(alice, atMs = 1_000)
        connect(alice, bob)
        alice.coordinator.onPeerAuthenticated(BOB_PID, bob.fingerprint)
        assertTrue(bob.coordinator.isVouched(Fingerprint(carol.fingerprint)))

        // Bob un-verifies Alice: the stored vouch stops counting (recomputed on read).
        bob.identityState.setVerifiedFingerprint(alice.fingerprint, false, nowMs = 5_000)
        assertTrue(bob.coordinator.validVouchers(Fingerprint(carol.fingerprint)).isEmpty())
        assertFalse(bob.coordinator.isVouched(Fingerprint(carol.fingerprint)))
    }

    @Test
    fun the_rate_limit_blocks_a_second_batch_to_the_same_peer() = runTest {
        val alice = Node().apply { build() }
        val bob = Node().apply { build() }
        val carol = Node().apply { build() }

        var deliveries = 0
        alice.verify(bob, atMs = 1_000)
        alice.verify(carol, atMs = 2_000)
        bob.verify(alice, atMs = 1_000)
        connect(alice, bob) { payload ->
            deliveries++
            bob.coordinator.onVouchAttestations(ALICE_PID, payload, 0)
        }

        alice.coordinator.onPeerAuthenticated(BOB_PID, bob.fingerprint)
        alice.coordinator.onPeerAuthenticated(BOB_PID, bob.fingerprint)
        assertEquals(1, deliveries)
    }

    @Test
    fun a_peer_advertising_capabilities_without_vouch_is_skipped() = runTest {
        val alice = Node().apply { build() }
        val bob = Node().apply { build() }
        val carol = Node().apply { build() }

        var deliveries = 0
        alice.verify(bob, atMs = 1_000)
        alice.verify(carol, atMs = 2_000)
        // Non-empty capability set lacking VOUCH → skip.
        connect(alice, bob, capabilities = PeerCapabilities.GROUPS) { deliveries++ }

        alice.coordinator.onPeerAuthenticated(BOB_PID, bob.fingerprint)
        assertEquals(0, deliveries)
    }

    @Test
    fun local_verification_triggers_a_pass_over_connected_verified_peers() = runTest {
        val alice = Node().apply { build() }
        val bob = Node().apply { build() }
        val carol = Node().apply { build() }

        // Bob is already connected and mutually verified when Alice later verifies Carol.
        alice.verify(bob, atMs = 1_000)
        bob.verify(alice, atMs = 1_000)
        connect(alice, bob)

        // Alice verifies Carol: the QR-completion trigger fires a vouch pass over connected Bob.
        alice.verify(carol, atMs = 2_000)

        assertTrue(bob.coordinator.isVouched(Fingerprint(carol.fingerprint)))
    }

    private fun peerInfo(peerID: String, capabilities: PeerCapabilities) = PeerInfo(
        id = peerID,
        nickname = peerID,
        isConnected = true,
        isDirectConnection = true,
        noisePublicKey = null,
        signingPublicKey = null,
        isVerifiedNickname = true,
        lastSeen = 0,
        capabilities = capabilities,
    )

    private companion object {
        const val ALICE_PID = "alicealicealice0"
        const val BOB_PID = "bobbobbobbobbob0"
    }

    /** In-memory [SecureKeyValueStore] (KSafe fails closed under Robolectric). Mirrors the prod shape. */
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

    /** Minimal in-memory [IdentityRepository]; only the verified-set flow matters to the coordinator. */
    private class FakeIdentityRepository : IdentityRepository {
        val verified = MutableStateFlow<Set<Fingerprint>>(emptySet())
        override fun observeVerifiedFingerprints(): Flow<Set<Fingerprint>> = verified
        override suspend fun myIdentity(): MyIdentity = throw NotImplementedError()
        override suspend fun isVerified(fingerprint: Fingerprint): Boolean = fingerprint in verified.value
        override suspend fun setVerified(fingerprint: Fingerprint, verified: Boolean) = Unit
        override suspend fun panicWipe() = Unit
    }

    /** Fake [MeshService]; only the vouch-relevant surface carries behaviour. */
    private class FakeMeshService : MeshService {
        var deliverVouchTo: ((ByteArray) -> Unit)? = null
        val fingerprintByPeer = mutableMapOf<String, String>()
        val peerInfo = mutableMapOf<String, PeerInfo>()

        override var vouchEventListener: VouchEventListener? = null
        override var verifyEventListener: VerifyEventListener? = null
        override var courierEventListener: CourierEventListener? = null

        override fun sendVouchAttestations(batchPayload: ByteArray, peerID: String) {
            deliverVouchTo?.invoke(batchPayload)
        }

        override fun sendCourierEnvelope(payload: ByteArray, toPeerID: String) = Unit

        override fun getPeerFingerprint(peerID: String): String? = fingerprintByPeer[peerID]
        override fun getPeerInfo(peerID: String): PeerInfo? = peerInfo[peerID]
        override fun connectedPeerIDs(): List<String> = fingerprintByPeer.keys.toList()

        override val myPeerID: String get() = "self"
        override val bleDebug: BleDebugHandle get() = throw NotImplementedError()
        override fun getPeerNicknames(): Map<String, String> = emptyMap()
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
}
