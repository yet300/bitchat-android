package com.app.transport.model

/**
 * Wire payload for public `VOICE_FRAME` packets and private Noise voice frames.
 *
 * Matches iOS `VoiceBurstPacket`: `[burstID:8][seq:UInt16 BE][flags:1][payload...]`.
 */
class VoiceBurstPacket(
    val burstId: ByteArray,
    val seq: UShort,
    val kind: Kind,
) {
    init {
        require(burstId.size == BURST_ID_SIZE) {
            "voice burst ID must be $BURST_ID_SIZE bytes, was ${burstId.size}"
        }
        if (kind is Kind.Frames) {
            require(kind.frames.isNotEmpty() && kind.frames.size <= MAX_FRAMES_PER_PACKET) {
                "voice burst packet must contain 1..$MAX_FRAMES_PER_PACKET frames"
            }
            require(kind.frames.all { it.isNotEmpty() && it.size <= UShort.MAX_VALUE.toInt() }) {
                "voice burst frames must be 1..${UShort.MAX_VALUE} bytes"
            }
        }
    }

    sealed class Kind {
        data class Start(val codec: Codec) : Kind()

        class Frames(val frames: List<ByteArray>) : Kind() {
            override fun equals(other: Any?): Boolean =
                other is Frames && frames.size == other.frames.size && frames.zip(other.frames).all { (left, right) ->
                    left.contentEquals(right)
                }

            override fun hashCode(): Int = frames.fold(1) { hash, frame -> 31 * hash + frame.contentHashCode() }
        }

        data class End(val totalDataPackets: UShort, val durationMs: UInt) : Kind()

        data object Canceled : Kind()
    }

    enum class Codec(val value: UByte) {
        AAC_LC_16K_MONO(0x01u),
        ;

        companion object {
            fun fromValue(value: UByte): Codec? = entries.find { it.value == value }
        }
    }

    fun encode(): ByteArray {
        val payload = when (val currentKind = kind) {
            is Kind.Start -> byteArrayOf(FLAG_START.toByte(), currentKind.codec.value.toByte())
            is Kind.Frames -> buildList<Byte> {
                add(FLAG_FRAMES.toByte())
                currentKind.frames.forEach { frame ->
                    add((frame.size ushr 8).toByte())
                    add(frame.size.toByte())
                    addAll(frame.toList())
                }
            }.toByteArray()
            is Kind.End -> byteArrayOf(
                FLAG_END.toByte(),
                (currentKind.totalDataPackets.toInt() ushr 8).toByte(),
                currentKind.totalDataPackets.toByte(),
                (currentKind.durationMs.toInt() ushr 24).toByte(),
                (currentKind.durationMs.toInt() ushr 16).toByte(),
                (currentKind.durationMs.toInt() ushr 8).toByte(),
                currentKind.durationMs.toByte(),
            )
            Kind.Canceled -> byteArrayOf(FLAG_CANCELED.toByte())
        }
        return burstId + byteArrayOf((seq.toInt() ushr 8).toByte(), seq.toByte()) + payload
    }

    override fun equals(other: Any?): Boolean =
        other is VoiceBurstPacket && seq == other.seq && kind == other.kind && burstId.contentEquals(other.burstId)

    override fun hashCode(): Int = 31 * (31 * burstId.contentHashCode() + seq.hashCode()) + kind.hashCode()

    companion object {
        const val BURST_ID_SIZE = 8
        const val MAX_FRAMES_PER_PACKET = 8

        private const val HEADER_SIZE = BURST_ID_SIZE + 3
        private const val FLAG_FRAMES = 0x00
        private const val FLAG_START = 0x01
        private const val FLAG_END = 0x02
        private const val FLAG_CANCELED = 0x04

        fun orNull(burstId: ByteArray, seq: UShort, kind: Kind): VoiceBurstPacket? =
            if (burstId.size != BURST_ID_SIZE || !framesAreValid(kind)) null else VoiceBurstPacket(burstId, seq, kind)

        fun decode(data: ByteArray): VoiceBurstPacket? {
            if (data.size < HEADER_SIZE) return null

            val burstId = data.copyOfRange(0, BURST_ID_SIZE)
            val seq = ((data[BURST_ID_SIZE].toInt() and 0xFF) shl 8 or
                (data[BURST_ID_SIZE + 1].toInt() and 0xFF)).toUShort()
            val payload = data.copyOfRange(HEADER_SIZE, data.size)
            val kind = when (val flags = data[BURST_ID_SIZE + 2].toInt() and 0xFF) {
                FLAG_START -> (Codec.fromValue(payload.firstOrNull()?.toUByte() ?: return null) ?: return null)
                    .let(Kind::Start)
                FLAG_END -> {
                    if (payload.size < 6) return null
                    val total = ((payload[0].toInt() and 0xFF) shl 8 or (payload[1].toInt() and 0xFF)).toUShort()
                    val duration = payload.sliceArray(2..5).fold(0u) { value, byte ->
                        (value shl 8) or (byte.toInt() and 0xFF).toUInt()
                    }
                    Kind.End(total, duration)
                }
                FLAG_CANCELED -> Kind.Canceled
                FLAG_FRAMES -> decodeFrames(payload) ?: return null
                else -> return null
            }

            return VoiceBurstPacket(burstId, seq, kind)
        }

        private fun decodeFrames(payload: ByteArray): Kind.Frames? {
            val frames = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < payload.size) {
                if (payload.size - offset < 2) return null
                val length = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                offset += 2
                if (length == 0 || payload.size - offset < length || frames.size >= MAX_FRAMES_PER_PACKET) return null
                frames += payload.copyOfRange(offset, offset + length)
                offset += length
            }
            return if (frames.isEmpty()) null else Kind.Frames(frames)
        }

        private fun framesAreValid(kind: Kind): Boolean =
            kind !is Kind.Frames || (kind.frames.isNotEmpty() && kind.frames.size <= MAX_FRAMES_PER_PACKET &&
                kind.frames.all { it.isNotEmpty() && it.size <= UShort.MAX_VALUE.toInt() })
    }
}
