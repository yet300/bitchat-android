@file:OptIn(ExperimentalTime::class)

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
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Packages/reassembles live voice (0x29) over the mesh. Outbound: a Start marker, the frames chunked
 * to at most [VoiceBurstPacket.MAX_FRAMES_PER_PACKET] per wire packet with an increasing sequence,
 * then an End marker. Inbound: groups incoming packets by (peer, burst id) and emits a [VoiceBurst]
 * once its End arrives. Stateless across bursts — nothing is persisted.
 *
 * Inbound pending map is hard-bounded (assembly count, frame bytes, age) so a flood of
 * incomplete Starts cannot grow without limit.
 */
@SingleIn(AppScope::class)
@Inject
internal class VoiceRepositoryImpl(
    private val meshService: MeshService,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : VoiceRepository {

    private data class PendingBurst(
        val frames: MutableList<ByteArray> = mutableListOf(),
        var bytes: Int = 0,
        val startedAtMs: Long,
    )

    companion object {
        /** Max concurrent incomplete bursts. */
        const val MAX_PENDING_ASSEMBLIES: Int = 32
        /** Max payload frames bytes per incomplete burst. */
        const val MAX_PENDING_BYTES_PER_BURST: Int = 512 * 1024
        /** Drop incomplete assemblies older than this. */
        const val PENDING_TIMEOUT_MS: Long = 30_000L
    }

    override val incomingBursts: Flow<VoiceBurst> = flow {
        val pending = linkedMapOf<String, PendingBurst>()
        meshService.publicVoiceFrames.collect { frame ->
            val packet = VoiceBurstPacket.decode(frame.payload) ?: return@collect
            val key = frame.peerId + ":" + packet.burstId.joinToString("") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
            val now = nowMs()
            prunePending(pending, now)

            when (val kind = packet.kind) {
                is VoiceBurstPacket.Kind.Start -> {
                    if (key !in pending) ensureCapacity(pending)
                    pending[key] = PendingBurst(startedAtMs = now)
                }
                is VoiceBurstPacket.Kind.Frames -> {
                    val entry = pending[key] ?: return@collect
                    for (f in kind.frames) {
                        if (entry.bytes + f.size > MAX_PENDING_BYTES_PER_BURST) {
                            pending.remove(key)
                            return@collect
                        }
                        entry.frames.add(f)
                        entry.bytes += f.size
                    }
                }
                is VoiceBurstPacket.Kind.End -> {
                    val entry = pending.remove(key)
                    if (entry != null && entry.frames.isNotEmpty()) {
                        emit(VoiceBurst(frame.peerId, entry.frames, kind.durationMs.toInt()))
                    }
                }
                VoiceBurstPacket.Kind.Canceled -> pending.remove(key)
            }
        }
    }

    private fun prunePending(pending: MutableMap<String, PendingBurst>, now: Long) {
        val cutoff = now - PENDING_TIMEOUT_MS
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.startedAtMs < cutoff) it.remove()
        }
    }

    private fun ensureCapacity(pending: MutableMap<String, PendingBurst>) {
        while (pending.size >= MAX_PENDING_ASSEMBLIES) {
            val oldest = pending.entries.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
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
