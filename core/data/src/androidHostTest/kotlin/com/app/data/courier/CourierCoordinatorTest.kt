@file:OptIn(ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.app.data.courier

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.app.common.AppDispatchers
import com.app.common.serialization.JsonConfig
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.crypto.secure.SecureKeyValueStore
import com.app.data.favorites.FavoritesPersistenceService
import com.app.database.BitMessageDatabase
import com.app.database.dao.CourierDao
import com.app.database.db.DatabaseDriverFactory
import com.app.database.db.DatabaseManager
import com.app.transport.courier.CourierEventListener
import com.app.transport.board.BoardEventListener
import com.app.transport.group.GroupEventListener
import com.app.transport.mesh.BleDebugHandle
import com.app.transport.mesh.MeshPingResult
import com.app.transport.mesh.MeshService
import com.app.transport.mesh.PeerInfo
import com.app.transport.model.BitchatFilePacket
import com.app.transport.model.CourierEnvelope
import com.app.transport.model.NoisePayload
import com.app.transport.model.NoisePayloadType
import com.app.transport.model.PeerCapabilities
import com.app.transport.model.PrivateMessagePacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.peerIdToRoutingBytes
import com.app.transport.verification.VerifyEventListener
import com.app.transport.vouch.VouchEventListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end courier store-and-forward across three instances — sender / courier / recipient — with
 * real seal/open, real store, and the real coordinator. "Meetings" are simulated by feeding the
 * captured directed packets between nodes. Mirrors the reference iOS CourierEndToEndTests.
 */
class CourierCoordinatorTest {

    private val dispatchers = AppDispatchers(io = UnconfinedTestDispatcher())

    private fun newDao(): CourierDao {
        val factory = object : DatabaseDriverFactory {
            private val driver: SqlDriver =
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BitMessageDatabase.Schema)
            override suspend fun create(): SqlDriver = driver
        }
        return CourierDao(DatabaseManager(factory, dispatchers), dispatchers)
    }

    private inner class Node(val pid: String) {
        val store = InMemoryStore()
        val encryption = EncryptionService(store, PeerFingerprintManager())
        val identityState = SecureIdentityStateManager(store)
        val favorites = FavoritesPersistenceService(identityState)
        val courierStore = CourierStore(newDao())
        val mesh = FakeCourierMesh()
        val coordinator = CourierCoordinator(mesh, encryption, favorites, courierStore, dispatchers)

        val noiseKey: ByteArray get() = encryption.getStaticPublicKey()!!
        val signingKey: ByteArray get() = encryption.getSigningPublicKey()!!

        /** Register [other] as a verified, directly-connected peer this node can see. */
        fun sees(other: Node, direct: Boolean = true) {
            mesh.peerInfo[other.pid] = PeerInfo(
                id = other.pid,
                nickname = other.pid,
                isConnected = direct,
                isDirectConnection = direct,
                noisePublicKey = other.noiseKey,
                signingPublicKey = other.signingKey,
                isVerifiedNickname = true,
                lastSeen = 0,
                capabilities = PeerCapabilities.NONE,
            )
        }
    }

    /** A signed 0x04 packet as [from] would emit when depositing [payload] with a courier. */
    private fun signedDeposit(from: Node, payload: ByteArray, toPid: String): BitchatPacket {
        val unsigned = BitchatPacket(
            version = 1u,
            type = MessageType.COURIER_ENVELOPE.value,
            senderID = peerIdToRoutingBytes(from.pid),
            recipientID = peerIdToRoutingBytes(toPid),
            timestamp = 1_000uL,
            payload = payload,
            signature = null,
            ttl = 7u,
        )
        val signature = from.encryption.signData(unsigned.toBinaryDataForSigning()!!)!!
        return unsigned.copy(signature = signature)
    }

    /** Simulate the mesh recipient-side open (MessageHandler): decrypt and decode the inner PM. */
    private fun open(recipient: Node, payload: ByteArray): Triple<String, String, ByteArray>? {
        val envelope = CourierEnvelope.decode(payload) ?: return null
        val (typed, senderStatic) = recipient.encryption.openCourierPayload(envelope.ciphertext) ?: return null
        val noisePayload = NoisePayload.decode(typed) ?: return null
        if (noisePayload.type != NoisePayloadType.PRIVATE_MESSAGE) return null
        val pm = PrivateMessagePacket.decode(noisePayload.data) ?: return null
        return Triple(pm.messageID, pm.content, senderStatic)
    }

    @Test
    fun full_cycle_sender_courier_recipient() = runTest {
        val sender = Node("sendersender0001")
        val courier = Node("couriercourier02")
        val recipient = Node("recipientreci003")

        // Sender seals for the offline recipient and deposits with the courier.
        sender.mesh.peerInfo[courier.pid] = PeerInfo(
            courier.pid, courier.pid, true, true, courier.noiseKey, courier.signingKey, true, 0, PeerCapabilities.NONE,
        )
        assertTrue(sender.coordinator.depositForRecipient("MSG-1", "meet at dawn", recipient.noiseKey, listOf(courier.pid)))
        val deposited = sender.mesh.sentTo(courier.pid).single()

        // Courier receives the deposit (verified sender) and carries it.
        courier.sees(sender)
        courier.coordinator.onCourierDeposit(sender.pid, signedDeposit(sender, deposited, courier.pid))
        assertEquals(1L, courier.courierStore.count())

        // Courier later meets the recipient directly: destructive handover.
        courier.sees(recipient)
        courier.coordinator.onCourierPeerAvailable(recipient.pid)
        assertEquals(0L, courier.courierStore.count(), "handed over, no longer carried")

        val handed = courier.mesh.sentTo(recipient.pid).single()
        val opened = open(recipient, handed)!!
        assertEquals("MSG-1", opened.first)
        assertEquals("meet at dawn", opened.second)
        assertContentEquals(sender.noiseKey, opened.third, "recipient authenticates the sender")
    }

    @Test
    fun deposit_rejected_from_untrusted_peer() = runTest {
        val sender = Node("sendersender0001")
        val courier = Node("couriercourier02")
        val recipient = Node("recipientreci003")

        sender.mesh.peerInfo[courier.pid] = PeerInfo(
            courier.pid, courier.pid, true, true, courier.noiseKey, courier.signingKey, true, 0, PeerCapabilities.NONE,
        )
        sender.coordinator.depositForRecipient("MSG-2", "hi", recipient.noiseKey, listOf(courier.pid))
        val deposited = sender.mesh.sentTo(courier.pid).single()

        // Courier sees the sender as UNVERIFIED and not a favorite → policy rejects, nothing stored.
        courier.mesh.peerInfo[sender.pid] = PeerInfo(
            sender.pid, sender.pid, true, true, sender.noiseKey, sender.signingKey,
            isVerifiedNickname = false, lastSeen = 0, capabilities = PeerCapabilities.NONE,
        )
        courier.coordinator.onCourierDeposit(sender.pid, signedDeposit(sender, deposited, courier.pid))
        assertEquals(0L, courier.courierStore.count())
    }

    @Test
    fun deposit_rejected_when_signature_does_not_verify() = runTest {
        val sender = Node("sendersender0001")
        val courier = Node("couriercourier02")
        val recipient = Node("recipientreci003")

        sender.mesh.peerInfo[courier.pid] = PeerInfo(
            courier.pid, courier.pid, true, true, courier.noiseKey, courier.signingKey, true, 0, PeerCapabilities.NONE,
        )
        sender.coordinator.depositForRecipient("MSG-3", "hi", recipient.noiseKey, listOf(courier.pid))
        val deposited = sender.mesh.sentTo(courier.pid).single()

        courier.sees(sender)
        val packet = signedDeposit(sender, deposited, courier.pid)
        val tampered = packet.copy(signature = packet.signature!!.copyOf().also { it[0] = (it[0] + 1).toByte() })
        courier.coordinator.onCourierDeposit(sender.pid, tampered)
        assertEquals(0L, courier.courierStore.count())
    }

    @Test
    fun spray_hands_copies_to_another_courier() = runTest {
        val sender = Node("sendersender0001")
        val courier = Node("couriercourier02")
        val courier2 = Node("courier2courier3")
        val recipient = Node("recipientreci003")

        sender.mesh.peerInfo[courier.pid] = PeerInfo(
            courier.pid, courier.pid, true, true, courier.noiseKey, courier.signingKey, true, 0, PeerCapabilities.NONE,
        )
        sender.coordinator.depositForRecipient("MSG-4", "carry me", recipient.noiseKey, listOf(courier.pid))
        val deposited = sender.mesh.sentTo(courier.pid).single()

        courier.sees(sender)
        courier.coordinator.onCourierDeposit(sender.pid, signedDeposit(sender, deposited, courier.pid))

        // Courier meets another verified courier (not the recipient): spray splits the budget.
        courier.sees(courier2)
        courier.coordinator.onCourierPeerAvailable(courier2.pid)

        val sprayed = courier.mesh.sentTo(courier2.pid).single()
        val env = CourierEnvelope.decode(sprayed)!!
        assertEquals(2u.toUByte(), env.copies, "half of the initial budget of 4")
        assertEquals(1L, courier.courierStore.count(), "still carried (non-destructive spray)")
    }

    @Test
    fun attempt_deposit_selects_couriers_and_is_idempotent() = runTest {
        val sender = Node("sendersender0001")
        val courier = Node("couriercourier02")
        val recipient = Node("recipientreci003")

        // Sender can see the courier (verified, connected) but the recipient is unreachable.
        sender.sees(courier)
        // The recipient's key is resolvable via a full 64-hex peerID.
        val recipientHex = recipient.noiseKey.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        assertTrue(sender.coordinator.attemptDeposit("MSG-9", "yo", recipientHex))
        assertEquals(1, sender.mesh.sentTo(courier.pid).size)
        // Second attempt for the same message id deposits nothing more (idempotent per message).
        assertFalse(sender.coordinator.attemptDeposit("MSG-9", "yo", recipientHex))
        assertEquals(1, sender.mesh.sentTo(courier.pid).size)

        // And the courier really can carry+deliver what was deposited.
        courier.sees(sender)
        courier.coordinator.onCourierDeposit(sender.pid, signedDeposit(sender, sender.mesh.sentTo(courier.pid).single(), courier.pid))
        assertEquals(1L, courier.courierStore.count())
    }

    @Test
    fun attempt_deposit_no_couriers_returns_false() = runTest {
        val sender = Node("sendersender0001")
        val recipient = Node("recipientreci003")
        val recipientHex = recipient.noiseKey.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertFalse(sender.coordinator.attemptDeposit("MSG-10", "yo", recipientHex))
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

    /** Fake [MeshService] capturing courier sends and serving a peer directory. */
    private class FakeCourierMesh : MeshService {
        val peerInfo = mutableMapOf<String, PeerInfo>()
        private val sent = mutableListOf<Pair<String, ByteArray>>()

        fun sentTo(peerID: String): List<ByteArray> = sent.filter { it.first == peerID }.map { it.second }

        override var courierEventListener: CourierEventListener? = null
        override var groupEventListener: GroupEventListener? = null
        override fun broadcastGroupMessage(payload: ByteArray) = Unit
        override fun sendGroupState(payload: ByteArray, toPeerID: String, isInvite: Boolean) = Unit
        override var boardEventListener: BoardEventListener? = null
        override fun sendBoardPayload(payload: ByteArray) = Unit
        override var prekeyEventListener: com.app.transport.prekey.PrekeyEventListener? = null
        override fun sendPrekeyBundle(payload: ByteArray) = Unit
        override fun sendCourierEnvelope(payload: ByteArray, toPeerID: String) {
            sent.add(toPeerID to payload)
        }

        override fun getPeerInfo(peerID: String): PeerInfo? = peerInfo[peerID]

        override var vouchEventListener: VouchEventListener? = null
        override var verifyEventListener: VerifyEventListener? = null
        override fun sendVouchAttestations(batchPayload: ByteArray, peerID: String) = Unit
        override fun getPeerFingerprint(peerID: String): String? = null
        override fun connectedPeerIDs(): List<String> = peerInfo.keys.toList()
        override val myPeerID: String get() = "self"
        override val bleDebug: BleDebugHandle get() = throw NotImplementedError()
        override fun getPeerNicknames(): Map<String, String> = emptyMap()
        override fun hasEstablishedSession(peerID: String): Boolean = false
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
