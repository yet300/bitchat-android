package com.app.data.board

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.app.common.AppDispatchers
import com.app.common.serialization.JsonConfig
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.secure.SecureKeyValueStore
import com.app.database.BitMessageDatabase
import com.app.database.dao.BoardDao
import com.app.database.db.DatabaseDriverFactory
import com.app.database.db.DatabaseManager
import com.app.domain.repository.SettingsRepository
import com.app.transport.board.BoardEventListener
import com.app.transport.mesh.BleDebugHandle
import com.app.transport.mesh.MeshPingResult
import com.app.transport.mesh.MeshService
import com.app.transport.mesh.PeerInfo
import com.app.transport.model.BoardWire
import com.app.transport.model.BitchatFilePacket
import com.app.transport.courier.CourierEventListener
import com.app.transport.group.GroupEventListener
import com.app.transport.verification.VerifyEventListener
import com.app.transport.vouch.VouchEventListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Board post -> tombstone -> hidden across two nodes over fake mesh, with real Ed25519: an author's
 * post reaches a reader, an author tombstone hides it on both sides, and a non-author cannot delete.
 * Retention/caps/author-only-delete are covered at the DAO layer (BoardDaoTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardCoordinatorTest {

    private fun TestScope.newNode(nick: String) =
        Node(nick, AppDispatchers(io = UnconfinedTestDispatcher(testScheduler)))

    private inner class Node(nick: String, dispatchers: AppDispatchers) {
        val encryption = EncryptionService(InMemoryStore(), PeerFingerprintManager())
        val mesh = FakeBoardMesh()
        val coordinator = BoardCoordinator(mesh, encryption, newBoardDao(dispatchers), FakeSettings(nick), dispatchers)

        /** Mimics the mesh delegate: decode + verify the inner signature, then hand to the coordinator. */
        fun receive(payload: ByteArray) {
            val wire = BoardWire.decode(payload) ?: return
            val ok = wire.verifySignature { k, d, s -> encryption.verifyEd25519Signature(s, d, k) }
            if (ok) coordinator.onBoardPacketReceived(payload)
        }
    }

    @Test
    fun post_reaches_reader_then_tombstone_hides_it() = runTest {
        val alice = newNode("alice")
        val bob = newNode("bob")
        alice.mesh.onBroadcast = { bob.receive(it) }
        bob.mesh.onBroadcast = { alice.receive(it) }

        assertTrue(alice.coordinator.createPost("hello board", geohash = "9q8yy", urgent = false, expiryDays = 3))
        runCurrent()

        val onAlice = alice.coordinator.posts("9q8yy")
        val onBob = bob.coordinator.posts("9q8yy")
        assertEquals(listOf("hello board"), onAlice.map { it.content })
        assertEquals(listOf("hello board"), onBob.map { it.content })
        assertTrue(onAlice.single().isMine)
        assertFalse(onBob.single().isMine, "reader does not own the post")

        val postId = onAlice.single().idHex
        // A non-author cannot delete someone else's post.
        assertFalse(bob.coordinator.deletePost(postId))
        runCurrent()
        assertEquals(1, bob.coordinator.posts("9q8yy").size)

        // The author deletes; the tombstone hides the post on both boards.
        assertTrue(alice.coordinator.deletePost(postId))
        runCurrent()
        assertTrue(alice.coordinator.posts("9q8yy").isEmpty())
        assertTrue(bob.coordinator.posts("9q8yy").isEmpty(), "tombstone hides the post for the reader too")
    }

    @Test
    fun urgent_posts_sort_first() = runTest {
        val alice = newNode("alice")
        alice.mesh.onBroadcast = { }
        assertTrue(alice.coordinator.createPost("normal", "9q8yy", urgent = false, expiryDays = 1))
        assertTrue(alice.coordinator.createPost("URGENT", "9q8yy", urgent = true, expiryDays = 1))
        runCurrent()
        assertEquals(listOf("URGENT", "normal"), alice.coordinator.posts("9q8yy").map { it.content })
    }

    // ---- fakes ----

    private fun newBoardDao(dispatchers: AppDispatchers): BoardDao {
        val factory = object : DatabaseDriverFactory {
            private val driver: SqlDriver =
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BitMessageDatabase.Schema)
            override suspend fun create(): SqlDriver = driver
        }
        return BoardDao(DatabaseManager(factory, dispatchers), dispatchers)
    }

    private class FakeSettings(private val nick: String) : SettingsRepository {
        override fun observeNickname(): Flow<String> = flowOf(nick)
        override suspend fun setNickname(value: String) = Unit
        override var locationServicesEnabled: Boolean = false
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

    private class FakeBoardMesh : MeshService {
        var onBroadcast: (ByteArray) -> Unit = {}
        override var boardEventListener: BoardEventListener? = null
        override fun sendBoardPayload(payload: ByteArray) = onBroadcast(payload)

        override var vouchEventListener: VouchEventListener? = null
        override var verifyEventListener: VerifyEventListener? = null
        override var courierEventListener: CourierEventListener? = null
        override var groupEventListener: GroupEventListener? = null
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
}
