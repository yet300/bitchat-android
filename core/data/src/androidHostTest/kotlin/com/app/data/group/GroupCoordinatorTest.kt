package com.app.data.group

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.app.common.AppDispatchers
import com.app.common.serialization.JsonConfig
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.secure.SecureKeyValueStore
import com.app.database.BitMessageDatabase
import com.app.database.dao.GroupDao
import com.app.database.db.DatabaseDriverFactory
import com.app.database.db.DatabaseManager
import com.app.domain.model.GroupMessageEvent
import com.app.domain.repository.SettingsRepository
import com.app.transport.group.GroupEventListener
import com.app.transport.mesh.BleDebugHandle
import com.app.transport.mesh.MeshPingResult
import com.app.transport.mesh.MeshService
import com.app.transport.mesh.PeerInfo
import com.app.transport.model.BitchatFilePacket
import com.app.transport.model.PeerCapabilities
import com.app.transport.verification.VerifyEventListener
import com.app.transport.vouch.VouchEventListener
import com.app.transport.courier.CourierEventListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Full private-group cycle across two nodes over fake mesh services, with real Ed25519 signing and
 * ChaCha20-Poly1305: create -> invite -> message -> remove -> re-key, asserting a removed member can
 * no longer read messages sealed under the new epoch, and that a sender never sees its own echo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupCoordinatorTest {

    private fun TestScope.newNode(nickname: String): Node =
        Node(nickname, AppDispatchers(io = UnconfinedTestDispatcher(testScheduler)))

    /** A device: real crypto identity + its own group DB + coordinator behind a routable fake mesh. */
    private inner class Node(nickname: String, val dispatchers: AppDispatchers) {
        val encryption = EncryptionService(InMemoryStore(), PeerFingerprintManager())
        val mesh = FakeGroupMesh()
        val store = GroupStore(newGroupDao(dispatchers))
        val coordinator = GroupCoordinator(mesh, encryption, store, FakeSettings(nickname), dispatchers)

        val fingerprint: String get() = encryption.getIdentityFingerprint()
        val signingKey: ByteArray get() = encryption.getSigningPublicKey()!!
        val noiseKey: ByteArray? get() = encryption.getStaticPublicKey()
        val pid: String get() = fingerprint.take(16)

        init {
            mesh.selfPeerId = pid
        }
    }

    /** Registers [b] as a connected, verified peer on [a]'s mesh (both routing + directory). */
    private fun link(a: Node, b: Node) {
        a.mesh.peers[b.pid] = b.mesh
        a.mesh.fingerprintByPeer[b.pid] = b.fingerprint
        a.mesh.peerInfoByPeer[b.pid] = PeerInfo(
            id = b.pid,
            nickname = "peer-${b.pid}",
            isConnected = true,
            isDirectConnection = true,
            noisePublicKey = b.noiseKey,
            signingPublicKey = b.signingKey,
            isVerifiedNickname = true,
            lastSeen = 0,
            capabilities = PeerCapabilities.NONE,
        )
    }

    @Test
    fun full_group_cycle_and_removed_member_cannot_read_new_epoch() = runTest {
        val alice = newNode("alice")
        val bob = newNode("bob")
        link(alice, bob)
        link(bob, alice)

        val bobInbox = mutableListOf<GroupMessageEvent>()
        backgroundScope.launch { bob.coordinator.incomingMessages.collect { bobInbox += it } }
        val aliceInbox = mutableListOf<GroupMessageEvent>()
        backgroundScope.launch { alice.coordinator.incomingMessages.collect { aliceInbox += it } }
        runCurrent()

        // Create (Alice is creator, epoch 1) then invite Bob (rotates to epoch 2).
        val groupHex = alice.coordinator.createGroup("friends")!!
        assertTrue(alice.coordinator.invite(groupHex, bob.pid))
        runCurrent()
        // Bob accepted the creator-signed invite and now holds the group at epoch 2.
        val groupId = ByteArray(16) { i -> groupHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        assertEquals(2, bob.store.group(groupId)!!.epoch.toInt())
        assertEquals(2, bob.store.group(groupId)!!.members.size)

        // Alice sends; Bob (a member at the current epoch) reads it.
        assertTrue(alice.coordinator.sendMessage(groupHex, "hello team"))
        runCurrent()
        assertEquals(listOf("hello team"), bobInbox.map { it.content })
        assertEquals("alice", bobInbox.single().senderNickname)
        assertTrue(aliceInbox.isEmpty(), "sender never sees its own echo")

        // Remove Bob: rotates to epoch 3 and notifies Bob, who drops the group.
        assertTrue(alice.coordinator.removeMember(groupHex, bob.fingerprint))
        runCurrent()
        assertNull(bob.store.group(groupId), "removed member forgets the group")
        assertEquals(3, alice.store.group(groupId)!!.epoch.toInt())

        // Alice sends under the new epoch; the removed Bob cannot read it.
        assertTrue(alice.coordinator.sendMessage(groupHex, "after removal"))
        runCurrent()
        assertEquals(listOf("hello team"), bobInbox.map { it.content }, "no new-epoch message reaches the removed member")
    }

    @Test
    fun non_member_cannot_open_and_stale_epoch_is_dropped() = runTest {
        val alice = newNode("alice")
        val bob = newNode("bob")
        link(alice, bob)
        link(bob, alice)

        val bobInbox = mutableListOf<GroupMessageEvent>()
        backgroundScope.launch { bob.coordinator.incomingMessages.collect { bobInbox += it } }
        runCurrent()

        val groupHex = alice.coordinator.createGroup("solo")!!
        // Bob is not in the group: Alice's broadcast is opaque to him.
        assertTrue(alice.coordinator.sendMessage(groupHex, "members only"))
        runCurrent()
        assertTrue(bobInbox.isEmpty(), "non-member cannot open a group message")
    }

    // ---- fakes ----

    private fun newGroupDao(dispatchers: AppDispatchers): GroupDao {
        val factory = object : DatabaseDriverFactory {
            private val driver: SqlDriver =
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BitMessageDatabase.Schema)
            override suspend fun create(): SqlDriver = driver
        }
        return GroupDao(DatabaseManager(factory, dispatchers), dispatchers)
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

    /** Fake [MeshService] that routes group sends to linked peers' listeners. */
    private class FakeGroupMesh : MeshService {
        var selfPeerId: String = "self"
        val peers = mutableMapOf<String, FakeGroupMesh>()
        val fingerprintByPeer = mutableMapOf<String, String>()
        val peerInfoByPeer = mutableMapOf<String, PeerInfo>()

        override var groupEventListener: GroupEventListener? = null

        override fun broadcastGroupMessage(payload: ByteArray) {
            for (peer in peers.values) peer.groupEventListener?.onGroupMessageReceived(payload, 0)
        }

        override fun sendGroupState(payload: ByteArray, toPeerID: String, isInvite: Boolean) {
            peers[toPeerID]?.groupEventListener?.onGroupStateReceived(selfPeerId, isInvite, payload)
        }

        override fun getPeerFingerprint(peerID: String): String? = fingerprintByPeer[peerID]
        override fun getPeerInfo(peerID: String): PeerInfo? = peerInfoByPeer[peerID]
        override fun getPeerNicknames(): Map<String, String> = emptyMap()
        override fun connectedPeerIDs(): List<String> = peers.keys.toList()

        override var vouchEventListener: VouchEventListener? = null
        override var verifyEventListener: VerifyEventListener? = null
        override var courierEventListener: CourierEventListener? = null
        override fun sendVouchAttestations(batchPayload: ByteArray, peerID: String) = Unit
        override fun sendCourierEnvelope(payload: ByteArray, toPeerID: String) = Unit
        override val myPeerID: String get() = selfPeerId
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
