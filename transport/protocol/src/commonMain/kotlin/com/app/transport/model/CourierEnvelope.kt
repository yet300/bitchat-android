package com.app.transport.model

import com.app.transport.crypto.HmacSha256

/**
 * TLV payload for store-and-forward courier envelopes (`MessageType.COURIER_ENVELOPE = 0x04`).
 *
 * A courier envelope lets a trusted peer (mutual favorite or verified) physically carry an encrypted
 * message to a recipient who is currently offline. The envelope is opaque to the courier: the only
 * routing information is a rotating [recipientTag] derived from the recipient's Noise static key and
 * the UTC day, so envelopes addressed to the same peer on different days do not correlate for
 * observers who don't already know that peer's public key.
 *
 * Byte-for-byte compatible with the reference iOS `CourierEnvelope` (BitFoundation).
 *
 * Wire format — TLV, each entry `type(1B) | length(2B big-endian) | value`; unknown types skipped:
 * - `0x01` recipientTag: 16 bytes (see [recipientTag])
 * - `0x02` expiry: 8 bytes big-endian, ms since epoch, discard-after
 * - `0x03` ciphertext: 1..[MAX_CIPHERTEXT_BYTES], opaque one-way Noise-X seal
 * - `0x04` copies: 1 byte spray-and-wait budget — **omitted when == 1** (carry-only)
 * - `0x05` prekeyID: 4 bytes big-endian — **omitted for v1** (static-sealed); a value means v2
 *   (sealed to a one-time prekey, forward secret)
 *
 * The [ciphertext] is an opaque one-way Noise X seal (`Noise_X_25519_ChaChaPoly_SHA256`), produced
 * and opened outside this pure wire codec (see the crypto layer); the sender's static identity rides
 * (encrypted) inside it, so the recipient authenticates the sender from the ciphertext alone.
 */
class CourierEnvelope(
    val recipientTag: ByteArray,
    val expiry: ULong,
    val ciphertext: ByteArray,
    copies: UByte = 1u,
    val prekeyID: UInt? = null,
) {
    /** Spray-and-wait copy budget, clamped to `1..`[MAX_COPIES] (1 = carry-only). */
    val copies: UByte = copies.coerceIn(1u, MAX_COPIES)

    /** The same envelope with a different remaining copy budget. */
    fun withCopies(copies: UByte): CourierEnvelope =
        CourierEnvelope(recipientTag, expiry, ciphertext, copies, prekeyID)

    fun isExpired(nowMs: Long): Boolean {
        // A timestamp past Long.MAX_VALUE ms is unrepresentable; treat as never-expiring-in-window
        // is unsafe, so an out-of-range expiry is considered reached. Matches the reference's
        // `nowMillis >= expiry` comparison for all representable values.
        if (expiry > Long.MAX_VALUE.toULong()) return nowMs < 0
        return nowMs >= expiry.toLong()
    }

    fun encode(): ByteArray? {
        if (recipientTag.size != TAG_LENGTH) return null
        if (ciphertext.isEmpty() || ciphertext.size > MAX_CIPHERTEXT_BYTES) return null

        val out = ArrayList<Byte>(3 * 3 + TAG_LENGTH + 8 + ciphertext.size)

        fun appendTlv(type: UByte, value: ByteArray) {
            out.add(type.toByte())
            out.add((value.size ushr 8).toByte())
            out.add(value.size.toByte())
            value.forEach(out::add)
        }

        appendTlv(TLV_RECIPIENT_TAG, recipientTag)
        appendTlv(TLV_EXPIRY, expiry.toBigEndianBytes())
        appendTlv(TLV_CIPHERTEXT, ciphertext)
        // Omitted when 1 so carry-only envelopes stay byte-identical to the pre-spray wire format
        // (old clients skip the TLV as unknown anyway).
        if (copies > 1u) appendTlv(TLV_COPIES, byteArrayOf(copies.toByte()))
        // Omitted for v1 static-sealed envelopes so they stay byte-identical to the pre-prekey format.
        prekeyID?.let { appendTlv(TLV_PREKEY_ID, it.toBigEndianBytes()) }

        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CourierEnvelope) return false
        return recipientTag.contentEquals(other.recipientTag) &&
            expiry == other.expiry &&
            ciphertext.contentEquals(other.ciphertext) &&
            copies == other.copies &&
            prekeyID == other.prekeyID
    }

    override fun hashCode(): Int {
        var result = recipientTag.contentHashCode()
        result = 31 * result + expiry.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + copies.hashCode()
        result = 31 * result + (prekeyID?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "CourierEnvelope(expiry=$expiry, ciphertext=${ciphertext.size}B, copies=$copies, prekeyID=$prekeyID)"

    companion object {
        const val TAG_LENGTH = 16

        /** Couriered messages are text-sized; media transfers are out of scope. */
        const val MAX_CIPHERTEXT_BYTES = 16 * 1024

        /** 24h — matches the outbox retention policy (`Outbox` TTL). */
        const val MAX_LIFETIME_SECONDS = 24L * 60 * 60

        /**
         * Cap on the copy budget a depositor can claim, so a malicious envelope cannot turn the
         * courier network into an amplifier.
         */
        const val MAX_COPIES: UByte = 8u

        private const val SECONDS_PER_DAY = 86_400L
        private val TAG_CONTEXT = "bitchat-courier-tag-v1".encodeToByteArray()

        private val TLV_RECIPIENT_TAG: UByte = 0x01u
        private val TLV_EXPIRY: UByte = 0x02u
        private val TLV_CIPHERTEXT: UByte = 0x03u
        private val TLV_COPIES: UByte = 0x04u
        private val TLV_PREKEY_ID: UByte = 0x05u

        fun decode(data: ByteArray): CourierEnvelope? {
            var recipientTag: ByteArray? = null
            var expiry: ULong? = null
            var ciphertext: ByteArray? = null
            var copies: UByte = 1u
            var prekeyID: UInt? = null

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
                    TLV_RECIPIENT_TAG -> {
                        if (length != TAG_LENGTH) return null
                        recipientTag = value
                    }
                    TLV_EXPIRY -> {
                        if (length != 8) return null
                        expiry = value.readBigEndianULong()
                    }
                    TLV_CIPHERTEXT -> {
                        if (length <= 0 || length > MAX_CIPHERTEXT_BYTES) return null
                        ciphertext = value
                    }
                    TLV_COPIES -> {
                        if (length != 1) return null
                        copies = value[0].toUByte()
                    }
                    TLV_PREKEY_ID -> {
                        if (length != 4) return null
                        prekeyID = value.readBigEndianUInt()
                    }
                    // Unknown TLV: skip for forward compatibility.
                }
                offset = valueEnd
            }

            return CourierEnvelope(
                recipientTag = recipientTag ?: return null,
                expiry = expiry ?: return null,
                ciphertext = ciphertext ?: return null,
                copies = copies,
                prekeyID = prekeyID,
            )
        }

        // MARK: - Recipient tags

        /** UTC day number used to rotate recipient tags. */
        fun epochDay(unixSeconds: Long): UInt = (maxOf(0L, unixSeconds) / SECONDS_PER_DAY).toUInt()

        /**
         * Rotating recipient hint for a given day. Computable only by parties who already know the
         * recipient's Noise static key. `HMAC-SHA256(key = noiseStaticKey, "bitchat-courier-tag-v1"
         * || epochDay(u32 BE))`, truncated to [TAG_LENGTH].
         */
        fun recipientTag(noiseStaticKey: ByteArray, epochDay: UInt): ByteArray {
            val message = TAG_CONTEXT + epochDay.toBigEndianBytes()
            return HmacSha256.mac(noiseStaticKey, message).copyOf(TAG_LENGTH)
        }

        /**
         * Tags to test when checking whether an envelope is addressed to a peer. Covers the adjacent
         * days so envelopes sealed near midnight (or across modest clock skew) still match while
         * being carried.
         */
        fun candidateTags(noiseStaticKey: ByteArray, nowMs: Long): List<ByteArray> {
            val day = epochDay(nowMs / 1000)
            val prev = if (day == 0u) 0u else day - 1u
            return listOf(prev, day, day + 1u).map { recipientTag(noiseStaticKey, it) }
        }

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
