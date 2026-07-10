package com.app.transport.model

import com.app.common.encoding.hexEncodedString

/**
 * A signed statement that the *sender of the enclosing Noise payload* has verified the identity
 * described here ("transitive verification").
 *
 * The voucher's identity is deliberately implicit: attestations only travel inside an authenticated
 * Noise session ([NoisePayloadType.VOUCH]), so the receiver verifies the Ed25519 signature against
 * the session peer's announce-bound signing key and stores the vouch keyed by that peer's
 * fingerprint. Nothing in the attestation names the voucher, so a captured attestation cannot be
 * replayed by a third party whose signing key doesn't match.
 *
 * Wire format — single attestation (TLV, 1-byte type + 1-byte length):
 * - `0x01` voucheeFingerprint: 32 bytes, SHA-256 of the vouchee's Noise static key
 * - `0x02` voucheeSigningKey: 32 bytes, Ed25519; anchors the vouch to a concrete identity
 * - `0x03` timestamp: 8 bytes big-endian, milliseconds since 1970
 * - `0x04` signature: 64 bytes, Ed25519 by the VOUCHER's signing key over
 *   `"bitchat-vouch-v1" | voucheeFingerprint | voucheeSigningKey | timestamp`
 *
 * Unknown TLV types are skipped for forward compatibility.
 *
 * Batch format (the [NoisePayloadType.VOUCH] payload body):
 * `[count: UInt8]` then per attestation `[length: UInt16 BE][attestation TLV]`.
 *
 * Signing and verification are injected rather than imported so this stays a pure wire codec.
 */
class VouchAttestation(
    val voucheeFingerprint: ByteArray,
    val voucheeSigningKey: ByteArray,
    val timestampMs: ULong,
    val signature: ByteArray,
) {

    val voucheeFingerprintHex: String get() = voucheeFingerprint.hexEncodedString()

    /** The exact bytes the voucher signs. */
    val signableBytes: ByteArray
        get() = signableBytes(voucheeFingerprint, voucheeSigningKey, timestampMs)

    /** Verifies the Ed25519 signature against the voucher's announce-bound signing key. */
    fun verifySignature(
        voucherSigningKey: ByteArray,
        verify: (publicKey: ByteArray, data: ByteArray, signature: ByteArray) -> Boolean,
    ): Boolean {
        if (voucherSigningKey.size != SIGNING_KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
        return verify(voucherSigningKey, signableBytes, signature)
    }

    /**
     * Whether the attestation is outside its validity window (older than [MAX_AGE_MS], or
     * timestamped implausibly far in the future).
     */
    fun isExpired(nowMs: Long): Boolean {
        // A timestamp past Long.MAX_VALUE ms is unrepresentable and implausibly far in the future;
        // the reference reaches the same verdict through its Double clock arithmetic.
        if (timestampMs > Long.MAX_VALUE.toULong()) return true
        val age = nowMs - timestampMs.toLong()
        return age > MAX_AGE_MS || age < -MAX_CLOCK_SKEW_MS
    }

    fun encode(): ByteArray? {
        if (voucheeFingerprint.size != FINGERPRINT_SIZE ||
            voucheeSigningKey.size != SIGNING_KEY_SIZE ||
            signature.size != SIGNATURE_SIZE
        ) {
            return null
        }
        val out = ArrayList<Byte>(TLV_OVERHEAD * 4 + FINGERPRINT_SIZE + SIGNING_KEY_SIZE + 8 + SIGNATURE_SIZE)

        fun appendTlv(type: UByte, value: ByteArray) {
            out.add(type.toByte())
            out.add(value.size.toByte())
            value.forEach(out::add)
        }

        appendTlv(TLV_VOUCHEE_FINGERPRINT, voucheeFingerprint)
        appendTlv(TLV_VOUCHEE_SIGNING_KEY, voucheeSigningKey)
        appendTlv(TLV_TIMESTAMP, timestampMs.toBigEndianBytes())
        appendTlv(TLV_SIGNATURE, signature)
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VouchAttestation) return false
        return voucheeFingerprint.contentEquals(other.voucheeFingerprint) &&
            voucheeSigningKey.contentEquals(other.voucheeSigningKey) &&
            timestampMs == other.timestampMs &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = voucheeFingerprint.contentHashCode()
        result = 31 * result + voucheeSigningKey.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }

    override fun toString(): String =
        "VouchAttestation(vouchee=${voucheeFingerprintHex.take(16)}..., timestampMs=$timestampMs)"

    companion object {
        const val SIGNING_CONTEXT = "bitchat-vouch-v1"

        /** Receiver-side expiry for attestations: 30 days. */
        const val MAX_AGE_MS: Long = 30L * 24 * 60 * 60 * 1000

        /** Tolerated clock skew for attestations timestamped in the future: 1 hour. */
        const val MAX_CLOCK_SKEW_MS: Long = 60L * 60 * 1000

        /** Upper bound of attestations carried/accepted in one batch payload. */
        const val MAX_BATCH_COUNT = 16

        const val FINGERPRINT_SIZE = 32
        const val SIGNING_KEY_SIZE = 32
        const val SIGNATURE_SIZE = 64

        private const val TLV_OVERHEAD = 2
        private val TLV_VOUCHEE_FINGERPRINT: UByte = 0x01u
        private val TLV_VOUCHEE_SIGNING_KEY: UByte = 0x02u
        private val TLV_TIMESTAMP: UByte = 0x03u
        private val TLV_SIGNATURE: UByte = 0x04u

        /** The exact bytes the voucher signs. */
        fun signableBytes(
            voucheeFingerprint: ByteArray,
            voucheeSigningKey: ByteArray,
            timestampMs: ULong,
        ): ByteArray {
            val context = SIGNING_CONTEXT.encodeToByteArray()
            val out = ByteArray(context.size + voucheeFingerprint.size + voucheeSigningKey.size + 8)
            var offset = 0
            context.copyInto(out, offset); offset += context.size
            voucheeFingerprint.copyInto(out, offset); offset += voucheeFingerprint.size
            voucheeSigningKey.copyInto(out, offset); offset += voucheeSigningKey.size
            timestampMs.toBigEndianBytes().copyInto(out, offset)
            return out
        }

        /**
         * Builds and signs an attestation. [sign] is the voucher's Ed25519 signing primitive.
         */
        fun build(
            voucheeFingerprint: ByteArray,
            voucheeSigningKey: ByteArray,
            timestampMs: ULong,
            sign: (ByteArray) -> ByteArray?,
        ): VouchAttestation? {
            if (voucheeFingerprint.size != FINGERPRINT_SIZE || voucheeSigningKey.size != SIGNING_KEY_SIZE) {
                return null
            }
            val message = signableBytes(voucheeFingerprint, voucheeSigningKey, timestampMs)
            val signature = sign(message) ?: return null
            if (signature.size != SIGNATURE_SIZE) return null
            return VouchAttestation(voucheeFingerprint, voucheeSigningKey, timestampMs, signature)
        }

        fun decode(data: ByteArray): VouchAttestation? {
            var fingerprint: ByteArray? = null
            var signingKey: ByteArray? = null
            var timestampMs: ULong? = null
            var signature: ByteArray? = null

            var offset = 0
            while (offset < data.size) {
                // Every TLV needs a type and a length byte.
                if (offset + 1 >= data.size) return null
                val type = data[offset].toUByte()
                val length = data[offset + 1].toInt() and 0xFF
                val valueStart = offset + 2
                val valueEnd = valueStart + length
                if (valueEnd > data.size) return null
                val value = data.copyOfRange(valueStart, valueEnd)

                when (type) {
                    TLV_VOUCHEE_FINGERPRINT -> {
                        if (value.size != FINGERPRINT_SIZE) return null
                        fingerprint = value
                    }
                    TLV_VOUCHEE_SIGNING_KEY -> {
                        if (value.size != SIGNING_KEY_SIZE) return null
                        signingKey = value
                    }
                    TLV_TIMESTAMP -> {
                        if (value.size != 8) return null
                        timestampMs = value.readBigEndianULong()
                    }
                    TLV_SIGNATURE -> {
                        if (value.size != SIGNATURE_SIZE) return null
                        signature = value
                    }
                    // Unknown TLV: skip for forward compatibility.
                }
                offset = valueEnd
            }

            return VouchAttestation(
                voucheeFingerprint = fingerprint ?: return null,
                voucheeSigningKey = signingKey ?: return null,
                timestampMs = timestampMs ?: return null,
                signature = signature ?: return null,
            )
        }

        /** Encodes up to [MAX_BATCH_COUNT] attestations into one payload body. */
        fun encodeList(attestations: List<VouchAttestation>): ByteArray? {
            if (attestations.isEmpty() || attestations.size > MAX_BATCH_COUNT) return null
            val out = ArrayList<Byte>()
            out.add(attestations.size.toByte())
            for (attestation in attestations) {
                val encoded = attestation.encode() ?: return null
                if (encoded.size > UShort.MAX_VALUE.toInt()) return null
                out.add((encoded.size ushr 8).toByte())
                out.add(encoded.size.toByte())
                encoded.forEach(out::add)
            }
            return out.toByteArray()
        }

        /**
         * Decodes a batch payload, dropping malformed entries and ignoring anything beyond
         * [MAX_BATCH_COUNT] (the sender-declared count is not trusted).
         */
        fun decodeList(data: ByteArray): List<VouchAttestation> {
            if (data.size <= 1) return emptyList()
            val declaredCount = data[0].toInt() and 0xFF
            val limit = minOf(declaredCount, MAX_BATCH_COUNT)
            val attestations = ArrayList<VouchAttestation>(limit)
            var offset = 1
            while (attestations.size < limit && offset < data.size) {
                if (offset + 2 > data.size) break
                val length = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                val entryStart = offset + 2
                val entryEnd = entryStart + length
                if (entryEnd > data.size) break
                decode(data.copyOfRange(entryStart, entryEnd))?.let(attestations::add)
                offset = entryEnd
            }
            return attestations
        }

        private fun ULong.toBigEndianBytes(): ByteArray =
            ByteArray(8) { i -> (this shr (8 * (7 - i))).toByte() }

        private fun ByteArray.readBigEndianULong(): ULong =
            fold(0uL) { acc, byte -> (acc shl 8) or (byte.toUByte().toULong()) }
    }
}
