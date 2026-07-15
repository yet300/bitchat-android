@file:OptIn(ExperimentalCoroutinesApi::class)

package com.app.data.voice

import com.app.transport.mesh.BleDebugHandle
import com.app.transport.mesh.MeshPingResult
import com.app.transport.mesh.MeshService
import com.app.transport.mesh.PeerInfo
import com.app.transport.model.BitchatFilePacket
import com.app.transport.model.VoiceBurstPacket
import com.app.transport.voice.PublicVoiceFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceRepositoryImplTest {

    /** Loops every broadcast payload straight back through [publicVoiceFrames] as if from [peerId]. */
    private class LoopbackMesh(private val peerId: String) : MeshService {
        val sent = mutableListOf<ByteArray>()
        private val frames = MutableSharedFlow<PublicVoiceFrame>(extraBufferCapacity = 64)
        override val publicVoiceFrames: Flow<PublicVoiceFrame> = frames

        fun emit(payload: ByteArray, fromPeerId: String = peerId) {
            assertTrue(frames.tryEmit(PublicVoiceFrame(fromPeerId, payload, timestampMs = 0)))
        }

        override fun broadcastVoiceFrame(payload: ByteArray) {
            sent += payload
            frames.tryEmit(PublicVoiceFrame(peerId, payload, timestampMs = 0))
        }

        override val myPeerID: String get() = "self"
        override val bleDebug: BleDebugHandle get() = throw NotImplementedError()
        override fun getPeerInfo(peerID: String): PeerInfo? = null
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
        override var verifyEventListener: com.app.transport.verification.VerifyEventListener? = null
        override fun getPeerFingerprint(peerID: String): String? = null
        override fun getStaticNoisePublicKey(): ByteArray? = null
        override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) = Unit
        override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) = Unit
        override var vouchEventListener: com.app.transport.vouch.VouchEventListener? = null
        override fun connectedPeerIDs(): List<String> = emptyList()
        override fun sendVouchAttestations(batchPayload: ByteArray, peerID: String) = Unit
        override var courierEventListener: com.app.transport.courier.CourierEventListener? = null
        override fun sendCourierEnvelope(payload: ByteArray, toPeerID: String) = Unit
        override var groupEventListener: com.app.transport.group.GroupEventListener? = null
        override fun broadcastGroupMessage(payload: ByteArray) = Unit
        override fun sendGroupState(payload: ByteArray, toPeerID: String, isInvite: Boolean) = Unit
        override var boardEventListener: com.app.transport.board.BoardEventListener? = null
        override fun sendBoardPayload(payload: ByteArray) = Unit
        override var prekeyEventListener: com.app.transport.prekey.PrekeyEventListener? = null
        override fun sendPrekeyBundle(payload: ByteArray) = Unit
    }

    private fun packet(
        burst: Int,
        kind: VoiceBurstPacket.Kind,
        sequence: Int = 0,
    ): ByteArray = VoiceBurstPacket(
        burstId = ByteArray(VoiceBurstPacket.BURST_ID_SIZE) { burst.toByte() },
        seq = sequence.toUShort(),
        kind = kind,
    ).encode()

    private fun LoopbackMesh.start(burst: Int) {
        emit(packet(burst, VoiceBurstPacket.Kind.Start(VoiceBurstPacket.Codec.AAC_LC_16K_MONO)))
    }

    private fun LoopbackMesh.frames(burst: Int, vararg frames: ByteArray) {
        emit(packet(burst, VoiceBurstPacket.Kind.Frames(frames.toList()), sequence = 1))
    }

    private fun LoopbackMesh.end(burst: Int, durationMs: Int = 100) {
        emit(packet(burst, VoiceBurstPacket.Kind.End(totalDataPackets = 1u, durationMs = durationMs.toUInt()), sequence = 2))
    }

    @Test
    fun broadcast_round_trips_into_a_single_reassembled_burst() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val mesh = LoopbackMesh(peerId = "peer1")
        val repo = VoiceRepositoryImpl(mesh)
        val received = mutableListOf<com.app.domain.model.VoiceBurst>()
        val job = launch(dispatcher) { repo.incomingBursts.collect { received += it } }

        val frames = listOf("frame-a".encodeToByteArray(), "frame-b".encodeToByteArray())
        repo.broadcast(frames, durationMs = 1234)

        assertEquals(1, received.size)
        assertEquals("peer1", received[0].peerId)
        assertEquals(1234, received[0].durationMs)
        assertEquals(listOf("frame-a", "frame-b"), received[0].frames.map { it.decodeToString() })
        job.cancel()
    }

    @Test
    fun broadcast_emits_start_frames_end_and_chunks_beyond_the_per_packet_limit() = runTest {
        val mesh = LoopbackMesh(peerId = "peer1")
        val repo = VoiceRepositoryImpl(mesh)

        // 9 frames > MAX_FRAMES_PER_PACKET (8) -> two Frames packets.
        val frames = (1..9).map { "f$it".encodeToByteArray() }
        repo.broadcast(frames, durationMs = 50)

        val kinds = mesh.sent.map { VoiceBurstPacket.decode(it)!!.kind }
        assertTrue(kinds.first() is VoiceBurstPacket.Kind.Start)
        assertTrue(kinds.last() is VoiceBurstPacket.Kind.End)
        assertEquals(2, kinds.count { it is VoiceBurstPacket.Kind.Frames })
        assertEquals(2, (kinds.last() as VoiceBurstPacket.Kind.End).totalDataPackets.toInt())
    }

    @Test
    fun empty_input_broadcasts_nothing() = runTest {
        val mesh = LoopbackMesh(peerId = "peer1")
        VoiceRepositoryImpl(mesh).broadcast(listOf(ByteArray(0)), durationMs = 10)
        assertTrue(mesh.sent.isEmpty())
    }

    @Test
    fun thirtyThirdIncompleteAssemblyEvictsOldestInsertion() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val mesh = LoopbackMesh(peerId = "peer1")
        val repo = VoiceRepositoryImpl(mesh)
        val received = mutableListOf<com.app.domain.model.VoiceBurst>()
        val job = launch(dispatcher) { repo.incomingBursts.collect { received += it } }

        repeat(VoiceRepositoryImpl.MAX_PENDING_ASSEMBLIES + 1) { mesh.start(it) }
        mesh.frames(0, "evicted".encodeToByteArray())
        mesh.end(0)
        mesh.frames(1, "retained".encodeToByteArray())
        mesh.end(1)

        assertEquals(listOf("retained"), received.flatMap { it.frames }.map { it.decodeToString() })
        job.cancel()
    }

    @Test
    fun burstOverByteBudgetIsRemovedAndEndDoesNotEmit() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val mesh = LoopbackMesh(peerId = "peer1")
        val repo = VoiceRepositoryImpl(mesh)
        val received = mutableListOf<com.app.domain.model.VoiceBurst>()
        val job = launch(dispatcher) { repo.incomingBursts.collect { received += it } }

        mesh.start(1)
        mesh.frames(1, *Array(VoiceBurstPacket.MAX_FRAMES_PER_PACKET) { ByteArray(UShort.MAX_VALUE.toInt()) })
        mesh.frames(1, ByteArray(9))
        mesh.end(1)

        assertTrue(received.isEmpty())
        job.cancel()
    }

    @Test
    fun assemblyOlderThanTimeoutIsRemovedUsingInjectedClock() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val mesh = LoopbackMesh(peerId = "peer1")
        var now = 1_000L
        val repo = VoiceRepositoryImpl(mesh, nowMs = { now })
        val received = mutableListOf<com.app.domain.model.VoiceBurst>()
        val job = launch(dispatcher) { repo.incomingBursts.collect { received += it } }

        mesh.start(1)
        now += VoiceRepositoryImpl.PENDING_TIMEOUT_MS + 1
        mesh.frames(1, "late".encodeToByteArray())
        mesh.end(1)

        assertTrue(received.isEmpty())
        job.cancel()
    }

    @Test
    fun duplicateStartAtCapacityDoesNotEvictUnrelatedBurst() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val mesh = LoopbackMesh(peerId = "peer1")
        val repo = VoiceRepositoryImpl(mesh)
        val received = mutableListOf<com.app.domain.model.VoiceBurst>()
        val job = launch(dispatcher) { repo.incomingBursts.collect { received += it } }

        repeat(VoiceRepositoryImpl.MAX_PENDING_ASSEMBLIES) { mesh.start(it) }
        mesh.start(VoiceRepositoryImpl.MAX_PENDING_ASSEMBLIES - 1)
        mesh.frames(0, "oldest-unrelated".encodeToByteArray())
        mesh.end(0)

        assertEquals(listOf("oldest-unrelated"), received.single().frames.map { it.decodeToString() })
        job.cancel()
    }

    @Test
    fun newBurstReassemblesAfterEvictionAndTimeout() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val mesh = LoopbackMesh(peerId = "peer1")
        var now = 1_000L
        val repo = VoiceRepositoryImpl(mesh, nowMs = { now })
        val received = mutableListOf<com.app.domain.model.VoiceBurst>()
        val job = launch(dispatcher) { repo.incomingBursts.collect { received += it } }

        repeat(VoiceRepositoryImpl.MAX_PENDING_ASSEMBLIES + 1) { mesh.start(it) }
        now += VoiceRepositoryImpl.PENDING_TIMEOUT_MS + 1
        mesh.start(100)
        mesh.frames(100, "fresh".encodeToByteArray())
        mesh.end(100)

        assertEquals(listOf("fresh"), received.single().frames.map { it.decodeToString() })
        job.cancel()
    }

    @Test
    fun framesWithoutStartDoNotCreateAssembly() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val mesh = LoopbackMesh(peerId = "peer1")
        val repo = VoiceRepositoryImpl(mesh)
        val received = mutableListOf<com.app.domain.model.VoiceBurst>()
        val job = launch(dispatcher) { repo.incomingBursts.collect { received += it } }

        mesh.frames(1, "orphan".encodeToByteArray())
        mesh.end(1)

        assertTrue(received.isEmpty())
        job.cancel()
    }
}
