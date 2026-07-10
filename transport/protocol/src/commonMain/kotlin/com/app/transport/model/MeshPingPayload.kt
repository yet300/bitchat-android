package com.app.transport.model

/**
 * Wire payload shared by the `ping` (0x26) and `pong` (0x27) message types.
 * Compatible with the reference iOS `MeshPingPayload`.
 *
 * Layout (9 bytes):
 * - 8 bytes: random nonce (a pong echoes the nonce of the ping it answers)
 * - 1 byte: origin TTL — the TTL the packet was launched with, so the receiver can compute the
 *   hop count as `originTTL - receivedTTL + 1`.
 *
 * Both directions are unencrypted and unsigned: the payload carries no private data, and the
 * unguessable nonce already binds a pong to a probe the local device actually sent.
 */
data class MeshPingPayload(
    val nonce: ByteArray,
    val originTTL: UByte,
) {
    init {
        require(nonce.size == NONCE_LENGTH) { "ping nonce must be $NONCE_LENGTH bytes, was ${nonce.size}" }
    }

    fun encode(): ByteArray = nonce + originTTL.toByte()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as MeshPingPayload
        return originTTL == other.originTTL && nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int = 31 * nonce.contentHashCode() + originTTL.hashCode()

    override fun toString(): String =
        "MeshPingPayload(nonce=${nonce.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }}, originTTL=$originTTL)"

    companion object {
        const val NONCE_LENGTH = 8
        private const val ENCODED_LENGTH = NONCE_LENGTH + 1

        /** Non-throwing counterpart of the constructor, mirroring the reference's failable init. */
        fun orNull(nonce: ByteArray, originTTL: UByte): MeshPingPayload? =
            if (nonce.size == NONCE_LENGTH) MeshPingPayload(nonce, originTTL) else null

        /**
         * Accepts payloads with trailing bytes so future revisions can extend the format without
         * breaking older clients (reference parity).
         */
        fun decode(data: ByteArray): MeshPingPayload? {
            if (data.size < ENCODED_LENGTH) return null
            return MeshPingPayload(
                nonce = data.copyOfRange(0, NONCE_LENGTH),
                originTTL = data[NONCE_LENGTH].toUByte(),
            )
        }

        /**
         * Number of links a packet crossed, derived from TTL decrements plus the final delivery
         * link (a directly connected peer is 1 hop away). Null when the TTLs are inconsistent
         * (received above origin).
         */
        fun hopCount(originTTL: UByte, receivedTTL: UByte): Int? {
            if (originTTL < receivedTTL) return null
            return (originTTL - receivedTTL).toInt() + 1
        }
    }
}
