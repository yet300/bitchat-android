package com.app.data.voice

import com.app.domain.model.VoiceBurst
import com.app.domain.repository.VoiceRepository
import com.app.transport.mesh.MeshService
import com.app.transport.model.VoiceBurstPacket
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * Packages/reassembles live voice (0x29) over the mesh. Outbound: a Start marker, the frames chunked
 * to at most [VoiceBurstPacket.MAX_FRAMES_PER_PACKET] per wire packet with an increasing sequence,
 * then an End marker. Inbound: groups incoming packets by (peer, burst id) and emits a [VoiceBurst]
 * once its End arrives. Stateless across bursts — nothing is persisted.
 */
@SingleIn(AppScope::class)
@Inject
internal class VoiceRepositoryImpl(
    private val meshService: MeshService,
) : VoiceRepository {

    override val incomingBursts: Flow<VoiceBurst> = flow {
        val pending = mutableMapOf<String, MutableList<ByteArray>>()
        meshService.publicVoiceFrames.collect { frame ->
            val packet = VoiceBurstPacket.decode(frame.payload) ?: return@collect
            val key = frame.peerId + ":" + packet.burstId.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            when (val kind = packet.kind) {
                is VoiceBurstPacket.Kind.Start -> pending[key] = mutableListOf()
                is VoiceBurstPacket.Kind.Frames -> pending.getOrPut(key) { mutableListOf() }.addAll(kind.frames)
                is VoiceBurstPacket.Kind.End -> {
                    val frames = pending.remove(key)
                    if (!frames.isNullOrEmpty()) {
                        emit(VoiceBurst(frame.peerId, frames, kind.durationMs.toInt()))
                    }
                }
                VoiceBurstPacket.Kind.Canceled -> pending.remove(key)
            }
        }
    }

    override suspend fun broadcast(frames: List<ByteArray>, durationMs: Int) {
        val usable = frames.filter { it.isNotEmpty() && it.size <= UShort.MAX_VALUE.toInt() }
        if (usable.isEmpty()) return

        val burstId = Random.nextBytes(VoiceBurstPacket.BURST_ID_SIZE)
        var seq = 0

        send(burstId, seq++, VoiceBurstPacket.Kind.Start(VoiceBurstPacket.Codec.AAC_LC_16K_MONO))

        val dataPackets = usable.chunked(VoiceBurstPacket.MAX_FRAMES_PER_PACKET)
        dataPackets.forEach { chunk ->
            send(burstId, seq++, VoiceBurstPacket.Kind.Frames(chunk))
        }

        send(burstId, seq++, VoiceBurstPacket.Kind.End(dataPackets.size.toUShort(), durationMs.toUInt()))
    }

    private fun send(burstId: ByteArray, seq: Int, kind: VoiceBurstPacket.Kind) {
        meshService.broadcastVoiceFrame(VoiceBurstPacket(burstId, seq.toUShort(), kind).encode())
    }
}
