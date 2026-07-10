package com.app.transport.model

/**
 * Bitfield describing which message types a REQUEST_SYNC round covers (TLV 0x04).
 *
 * Bit map is EXACTLY the reference iOS `Sync/SyncTypeFlags.swift`:
 * announce=0, message=1, leave=2, noiseHandshake=3, noiseEncrypted=4, fragment=5,
 * requestSync=6, fileTransfer=7, boardPost=8, prekeyBundle=9, groupMessage=10.
 *
 * Wire form: little-endian u64 with trailing zero bytes trimmed, 1-8 bytes.
 * Bits with no known type are masked out at construction, so a newer peer's extra
 * bits decode to "no type" instead of living on as phantom membership (iOS parity).
 */
class SyncTypeFlags private constructor(val rawValue: ULong) {

    fun contains(other: SyncTypeFlags): Boolean =
        (rawValue and other.rawValue) == other.rawValue && other.rawValue != 0uL

    fun union(other: SyncTypeFlags): SyncTypeFlags = fromRaw(rawValue or other.rawValue)

    fun intersection(other: SyncTypeFlags): SyncTypeFlags = fromRaw(rawValue and other.rawValue)

    /**
     * Little-endian u64, trailing zero bytes trimmed, 1-8 bytes.
     * Null when no bit is set (iOS returns nil and omits the TLV).
     */
    fun toData(): ByteArray? {
        if (rawValue == 0uL) return null
        var value = rawValue
        val bytes = ArrayList<Byte>(8)
        while (value > 0uL && bytes.size < 8) {
            bytes.add((value and 0xFFuL).toByte())
            value = value shr 8
        }
        return bytes.toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is SyncTypeFlags && other.rawValue == rawValue
    override fun hashCode(): Int = rawValue.hashCode()
    override fun toString(): String = "SyncTypeFlags(0x${rawValue.toString(16)})"

    companion object {
        // Union of every bit that maps to a known message type (bits 0..10).
        private const val KNOWN_TYPE_MASK: ULong = 0x7FFuL

        fun fromRaw(rawValue: ULong): SyncTypeFlags = SyncTypeFlags(rawValue and KNOWN_TYPE_MASK)

        val announce: SyncTypeFlags = fromRaw(1uL shl 0)
        val message: SyncTypeFlags = fromRaw(1uL shl 1)
        val leave: SyncTypeFlags = fromRaw(1uL shl 2)
        val noiseHandshake: SyncTypeFlags = fromRaw(1uL shl 3)
        val noiseEncrypted: SyncTypeFlags = fromRaw(1uL shl 4)
        val fragment: SyncTypeFlags = fromRaw(1uL shl 5)
        val requestSync: SyncTypeFlags = fromRaw(1uL shl 6)
        val fileTransfer: SyncTypeFlags = fromRaw(1uL shl 7)
        val boardPost: SyncTypeFlags = fromRaw(1uL shl 8)
        val prekeyBundle: SyncTypeFlags = fromRaw(1uL shl 9)
        val groupMessage: SyncTypeFlags = fromRaw(1uL shl 10)

        /** announce|message = raw 0x03 -> toData() = single byte 0x03. */
        val publicMessages: SyncTypeFlags = fromRaw(0x03uL)

        /** Accepts 1-8 bytes (little-endian); unknown high bits map to no type. */
        fun decode(data: ByteArray): SyncTypeFlags? {
            if (data.size !in 1..8) return null
            var raw = 0uL
            for (i in data.indices) {
                raw = raw or ((data[i].toInt() and 0xFF).toULong() shl (i * 8))
            }
            return fromRaw(raw)
        }
    }
}
