package com.app.transport.model

/** Wire payload for MessageType 0x28, byte-compatible with the current iOS carrier codec. */
class NostrCarrierPacket(
    val direction: Direction,
    val geohash: String,
    val eventJson: ByteArray,
) {
    enum class Direction(val value: UByte) {
        TO_GATEWAY(0x01u),
        FROM_GATEWAY(0x02u),
        TO_BRIDGE(0x03u),
        FROM_BRIDGE(0x04u);

        companion object {
            fun fromValue(value: UByte): Direction? = entries.firstOrNull { it.value == value }
        }
    }

    init {
        require(isValid(geohash, eventJson))
    }

    fun encode(): ByteArray {
        val geohashBytes = geohash.encodeToByteArray()
        val result = ArrayList<Byte>(eventJson.size + geohashBytes.size + 12)
        appendTlv(result, TYPE_DIRECTION, byteArrayOf(direction.value.toByte()))
        appendTlv(result, TYPE_GEOHASH, geohashBytes)
        appendTlv(result, TYPE_EVENT_JSON, eventJson)
        return result.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is NostrCarrierPacket &&
            direction == other.direction &&
            geohash == other.geohash &&
            eventJson.contentEquals(other.eventJson)

    override fun hashCode(): Int = 31 * (31 * direction.hashCode() + geohash.hashCode()) + eventJson.contentHashCode()

    companion object {
        const val MAX_EVENT_JSON_BYTES = 16 * 1024
        const val MAX_GEOHASH_LENGTH = 12

        private const val TYPE_DIRECTION = 0x01
        private const val TYPE_GEOHASH = 0x02
        private const val TYPE_EVENT_JSON = 0x03

        fun orNull(direction: Direction, geohash: String, eventJson: ByteArray): NostrCarrierPacket? =
            if (isValid(geohash, eventJson)) {
                NostrCarrierPacket(direction, geohash, eventJson.copyOf())
            } else {
                null
            }

        fun decode(data: ByteArray): NostrCarrierPacket? {
            var offset = 0
            var direction: Direction? = null
            var geohash: String? = null
            var eventJson: ByteArray? = null

            while (offset + 3 <= data.size) {
                val type = data[offset].toInt() and 0xff
                val length = ((data[offset + 1].toInt() and 0xff) shl 8) or
                    (data[offset + 2].toInt() and 0xff)
                offset += 3
                if (offset + length > data.size) return null
                val value = data.copyOfRange(offset, offset + length)
                offset += length

                when (type) {
                    TYPE_DIRECTION -> {
                        if (value.size != 1) return null
                        direction = Direction.fromValue(value[0].toUByte()) ?: return null
                    }
                    TYPE_GEOHASH -> geohash = value.decodeToStringOrNull() ?: return null
                    TYPE_EVENT_JSON -> eventJson = value
                }
            }

            if (offset != data.size) return null
            return orNull(direction ?: return null, geohash ?: return null, eventJson ?: return null)
        }

        private fun isValid(geohash: String, eventJson: ByteArray): Boolean {
            val geohashSize = geohash.encodeToByteArray().size
            return geohashSize in 1..MAX_GEOHASH_LENGTH && eventJson.size in 1..MAX_EVENT_JSON_BYTES
        }

        private fun appendTlv(destination: MutableList<Byte>, type: Int, value: ByteArray) {
            destination += type.toByte()
            destination += ((value.size ushr 8) and 0xff).toByte()
            destination += (value.size and 0xff).toByte()
            destination.addAll(value.asList())
        }

        private fun ByteArray.decodeToStringOrNull(): String? = try {
            decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
