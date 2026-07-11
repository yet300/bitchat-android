package com.app.transport.model

/**
 * TLV payload for gossiped one-time prekey bundles (`MessageType.PREKEY_BUNDLE = 0x24`).
 *
 * A bundle publishes a batch of one-time Curve25519 public prekeys bound to the owner's Noise
 * static key by an Ed25519 signature over domain-prefixed canonical bytes. Anyone holding the
 * owner's announce-verified signing key can verify a bundle offline, which is what lets bundles
 * spread and persist mesh-wide via gossip sync while the owner is away. Senders seal courier mail
 * (0x04) to one of these prekeys — one-way Noise X — instead of the owner's long-lived static key,
 * restoring forward secrecy for async first contact.
 *
 * Byte-for-byte compatible with the reference iOS `PrekeyBundle` (BitFoundation).
 *
 * Wire format — TLV, each entry `type(1B) | length(2B big-endian) | value`; unknown types skipped
 * (NOTE: 2-byte lengths, unlike [VouchAttestation]'s 1-byte — the asymmetry is in the reference):
 * - `0x01` noiseStaticPublicKey: 32 bytes — whose prekeys these are
 * - `0x02` prekeys: `n * 36` bytes, `1 <= n <=` [MAX_PREKEYS], entry = `u32 BE id | 32B pubkey`
 * - `0x03` generatedAt: 8 bytes big-endian, ms since epoch; strictly newer replaces cached copies
 * - `0x04` signature: 64 bytes, Ed25519 by the owner's announce-bound signing key over
 *   [signableBytes]
 *
 * Signing and verification are injected rather than imported so this stays a pure wire codec.
 */
class PrekeyBundle(
    val noiseStaticPublicKey: ByteArray,
    val prekeys: List<Prekey>,
    val generatedAt: ULong,
    val signature: ByteArray,
) {

    /** One one-time Curve25519 public prekey with its owner-assigned sequential id. */
    class Prekey(val id: UInt, val publicKey: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Prekey) return false
            return id == other.id && publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int = 31 * id.hashCode() + publicKey.contentHashCode()

        override fun toString(): String = "Prekey(id=$id)"
    }

    /** The exact bytes the owner signs. */
    val signableBytes: ByteArray
        get() = signableBytes(noiseStaticPublicKey, prekeys, generatedAt)

    /** Verifies the Ed25519 signature against the owner's announce-bound signing key. */
    fun verifySignature(
        ownerSigningKey: ByteArray,
        verify: (publicKey: ByteArray, data: ByteArray, signature: ByteArray) -> Boolean,
    ): Boolean {
        if (ownerSigningKey.size != KEY_LENGTH || signature.size != SIGNATURE_LENGTH) return false
        return verify(ownerSigningKey, signableBytes, signature)
    }

    fun encode(): ByteArray? {
        if (noiseStaticPublicKey.size != KEY_LENGTH ||
            signature.size != SIGNATURE_LENGTH ||
            prekeys.isEmpty() || prekeys.size > MAX_PREKEYS ||
            prekeys.any { it.publicKey.size != KEY_LENGTH }
        ) {
            return null
        }

        val entries = encodePrekeyEntries()
        val out = ArrayList<Byte>(TLV_OVERHEAD * 4 + KEY_LENGTH + entries.size + 8 + SIGNATURE_LENGTH)

        fun appendTlv(type: UByte, value: ByteArray) {
            out.add(type.toByte())
            out.add((value.size ushr 8).toByte())
            out.add(value.size.toByte())
            value.forEach(out::add)
        }

        appendTlv(TLV_NOISE_STATIC_PUBLIC_KEY, noiseStaticPublicKey)
        appendTlv(TLV_PREKEYS, entries)
        appendTlv(TLV_GENERATED_AT, generatedAt.toBigEndianBytes())
        appendTlv(TLV_SIGNATURE, signature)
        return out.toByteArray()
    }

    private fun encodePrekeyEntries(): ByteArray {
        val entries = ByteArray(prekeys.size * PREKEY_ENTRY_LENGTH)
        var offset = 0
        for (prekey in prekeys) {
            prekey.id.toBigEndianBytes().copyInto(entries, offset); offset += 4
            prekey.publicKey.copyInto(entries, offset); offset += KEY_LENGTH
        }
        return entries
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PrekeyBundle) return false
        return noiseStaticPublicKey.contentEquals(other.noiseStaticPublicKey) &&
            prekeys == other.prekeys &&
            generatedAt == other.generatedAt &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = noiseStaticPublicKey.contentHashCode()
        result = 31 * result + prekeys.hashCode()
        result = 31 * result + generatedAt.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }

    override fun toString(): String =
        "PrekeyBundle(prekeys=${prekeys.size}, generatedAt=$generatedAt)"

    companion object {
        /**
         * Domain separation for the bundle signature so it can never be confused with announce or
         * packet signatures. Unlike the vouch transcript, the reference length-prefixes this
         * context inside [signableBytes] — the asymmetry is real and must be preserved.
         */
        const val SIGNING_CONTEXT = "bitchat-prekey-bundle-v1"

        const val KEY_LENGTH = 32
        const val SIGNATURE_LENGTH = 64
        const val MAX_PREKEYS = 8
        private const val PREKEY_ENTRY_LENGTH = 4 + KEY_LENGTH

        private const val TLV_OVERHEAD = 3
        private val TLV_NOISE_STATIC_PUBLIC_KEY: UByte = 0x01u
        private val TLV_PREKEYS: UByte = 0x02u
        private val TLV_GENERATED_AT: UByte = 0x03u
        private val TLV_SIGNATURE: UByte = 0x04u

        /**
         * Canonical bytes covered by the Ed25519 signature:
         * `u8(24) | "bitchat-prekey-bundle-v1" | ownerKey(32) | u8(count) | entries | u64 BE
         * generatedAt` where each entry is `u32 BE id | 32B pubkey`.
         */
        fun signableBytes(
            noiseStaticPublicKey: ByteArray,
            prekeys: List<Prekey>,
            generatedAt: ULong,
        ): ByteArray {
            val context = SIGNING_CONTEXT.encodeToByteArray()
            val out = ArrayList<Byte>(1 + context.size + KEY_LENGTH + 1 + prekeys.size * PREKEY_ENTRY_LENGTH + 8)
            out.add(context.size.toByte())
            context.forEach(out::add)
            paddedKey(noiseStaticPublicKey).forEach(out::add)
            out.add(prekeys.size.toByte())
            for (prekey in prekeys.take(255)) {
                prekey.id.toBigEndianBytes().forEach(out::add)
                paddedKey(prekey.publicKey).forEach(out::add)
            }
            generatedAt.toBigEndianBytes().forEach(out::add)
            return out.toByteArray()
        }

        /**
         * Builds and signs a bundle. [sign] is the owner's Ed25519 signing primitive over the
         * canonical [signableBytes].
         */
        fun build(
            noiseStaticPublicKey: ByteArray,
            prekeys: List<Prekey>,
            generatedAt: ULong,
            sign: (ByteArray) -> ByteArray?,
        ): PrekeyBundle? {
            if (noiseStaticPublicKey.size != KEY_LENGTH ||
                prekeys.isEmpty() || prekeys.size > MAX_PREKEYS ||
                prekeys.any { it.publicKey.size != KEY_LENGTH }
            ) {
                return null
            }
            val signature = sign(signableBytes(noiseStaticPublicKey, prekeys, generatedAt)) ?: return null
            if (signature.size != SIGNATURE_LENGTH) return null
            return PrekeyBundle(noiseStaticPublicKey, prekeys, generatedAt, signature)
        }

        fun decode(data: ByteArray): PrekeyBundle? {
            var noiseStaticPublicKey: ByteArray? = null
            var prekeys: List<Prekey>? = null
            var generatedAt: ULong? = null
            var signature: ByteArray? = null

            var offset = 0
            while (offset < data.size) {
                // Every TLV needs a type byte and a 2-byte length.
                if (offset + 3 > data.size) return null
                val type = data[offset].toUByte()
                val length = ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset + 2].toInt() and 0xFF)
                val valueStart = offset + 3
                val valueEnd = valueStart + length
                if (valueEnd > data.size) return null
                val value = data.copyOfRange(valueStart, valueEnd)

                when (type) {
                    TLV_NOISE_STATIC_PUBLIC_KEY -> {
                        if (length != KEY_LENGTH) return null
                        noiseStaticPublicKey = value
                    }
                    TLV_PREKEYS -> {
                        if (length <= 0 || length % PREKEY_ENTRY_LENGTH != 0 ||
                            length / PREKEY_ENTRY_LENGTH > MAX_PREKEYS
                        ) {
                            return null
                        }
                        prekeys = decodePrekeyEntries(value)
                    }
                    TLV_GENERATED_AT -> {
                        if (length != 8) return null
                        generatedAt = value.readBigEndianULong()
                    }
                    TLV_SIGNATURE -> {
                        if (length != SIGNATURE_LENGTH) return null
                        signature = value
                    }
                    // Unknown TLV: skip for forward compatibility.
                }
                offset = valueEnd
            }

            val parsedPrekeys = prekeys ?: return null
            if (parsedPrekeys.isEmpty()) return null
            // Duplicate prekey IDs would let one consumed ID shadow another.
            if (parsedPrekeys.mapTo(HashSet()) { it.id }.size != parsedPrekeys.size) return null
            return PrekeyBundle(
                noiseStaticPublicKey = noiseStaticPublicKey ?: return null,
                prekeys = parsedPrekeys,
                generatedAt = generatedAt ?: return null,
                signature = signature ?: return null,
            )
        }

        private fun decodePrekeyEntries(value: ByteArray): List<Prekey> {
            val parsed = ArrayList<Prekey>(value.size / PREKEY_ENTRY_LENGTH)
            var entryStart = 0
            while (entryStart < value.size) {
                val id = value.copyOfRange(entryStart, entryStart + 4).readBigEndianUInt()
                val key = value.copyOfRange(entryStart + 4, entryStart + PREKEY_ENTRY_LENGTH)
                parsed.add(Prekey(id, key))
                entryStart += PREKEY_ENTRY_LENGTH
            }
            return parsed
        }

        /** Truncates to 32 or right-pads with zeros — defensive, never triggers on valid input. */
        private fun paddedKey(key: ByteArray): ByteArray =
            if (key.size == KEY_LENGTH) key else key.copyOf(KEY_LENGTH)

        private fun ULong.toBigEndianBytes(): ByteArray =
            ByteArray(8) { i -> (this shr (8 * (7 - i))).toByte() }

        private fun UInt.toBigEndianBytes(): ByteArray =
            ByteArray(4) { i -> (this shr (8 * (3 - i))).toByte() }

        private fun ByteArray.readBigEndianULong(): ULong =
            fold(0uL) { acc, byte -> (acc shl 8) or (byte.toUByte().toULong()) }

        private fun ByteArray.readBigEndianUInt(): UInt =
            fold(0u) { acc, byte -> (acc shl 8) or (byte.toUByte().toUInt()) }
    }
}
