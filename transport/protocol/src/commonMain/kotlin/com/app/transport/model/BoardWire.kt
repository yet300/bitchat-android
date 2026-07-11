package com.app.transport.model

/**
 * Wire format for the geohash bulletin board (`MessageType.BOARD_POST = 0x23`) — byte-for-byte
 * compatible with the reference iOS `Protocols/BoardPackets.swift`.
 *
 * A board payload is either a signed [BoardPostPacket] (a public notice designed to outlive chat:
 * it stays until its author-chosen expiry, max 7 days) or a signed [BoardTombstonePacket] (an
 * author-only deletion marker). Both are self-authenticating: the payload embeds the author's
 * Ed25519 public key and a signature over canonical, length-prefixed bytes, so verification never
 * depends on the author still being reachable. Signature verification is injected as a
 * `(publicKey, data, signature) -> Boolean` lambda so this module stays crypto-key-free.
 *
 * TLV layout (type u8, length u16 big-endian, value); unknown TLVs are skipped:
 *  - 0x01 kind (u8): 0x01 post, 0x02 tombstone
 *  - 0x02 postID (16B)
 *  - 0x03 geohash (UTF-8, empty = mesh-local board, ≤12 chars)          [post]
 *  - 0x04 content (UTF-8, 1..512 B)                                       [post]
 *  - 0x05 authorSigningKey (32B Ed25519)
 *  - 0x06 authorNickname (UTF-8, ≤64 B)                                  [post]
 *  - 0x07 createdAt (u64 BE ms)                                          [post]
 *  - 0x08 expiresAt (u64 BE ms, ≤ createdAt + 7 days)                    [post]
 *  - 0x09 flags (u8, bit0 = urgent)                                      [post]
 *  - 0x0A signature (64B Ed25519)
 *  - 0x0B deletedAt (u64 BE ms)                                          [tombstone]
 */

object BoardWireConstants {
    const val POST_ID_LENGTH = 16
    const val SIGNING_KEY_LENGTH = 32
    const val SIGNATURE_LENGTH = 64
    const val CONTENT_MAX_BYTES = 512
    const val NICKNAME_MAX_BYTES = 64
    const val GEOHASH_MAX_LENGTH = 12

    /** Posts may live at most 7 days past their creation timestamp. */
    const val MAX_LIFETIME_MS: Long = 7L * 24 * 60 * 60 * 1000

    val POST_SIGNING_CONTEXT = "bitchat-board-v1"
    val TOMBSTONE_SIGNING_CONTEXT = "bitchat-board-del-v1"

    /** Base32 geohash alphabet (no a/i/l/o). */
    val GEOHASH_ALPHABET: Set<Char> = "0123456789bcdefghjkmnpqrstuvwxyz".toSet()

    const val URGENT_FLAG: Int = 0x01
}

/** A signed, persistent bulletin-board notice. */
class BoardPostPacket(
    val postID: ByteArray,
    /** Empty scopes the post to the mesh-local board. */
    val geohash: String,
    val content: String,
    val authorSigningKey: ByteArray,
    val authorNickname: String,
    val createdAt: ULong,
    val expiresAt: ULong,
    val flags: UByte,
    val signature: ByteArray,
) {
    val isUrgent: Boolean get() = (flags.toInt() and BoardWireConstants.URGENT_FLAG) != 0

    /** Canonical bytes the Ed25519 signature covers (variable fields length-prefixed). */
    val signingBytes: ByteArray
        get() = signingBytes(postID, geohash, content, authorSigningKey, authorNickname, createdAt, expiresAt, flags)

    fun verifySignature(verify: (ByteArray, ByteArray, ByteArray) -> Boolean): Boolean =
        signature.size == BoardWireConstants.SIGNATURE_LENGTH && verify(authorSigningKey, signingBytes, signature)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BoardPostPacket) return false
        return postID.contentEquals(other.postID) && geohash == other.geohash && content == other.content &&
            authorSigningKey.contentEquals(other.authorSigningKey) && authorNickname == other.authorNickname &&
            createdAt == other.createdAt && expiresAt == other.expiresAt && flags == other.flags &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var r = postID.contentHashCode()
        r = 31 * r + geohash.hashCode(); r = 31 * r + content.hashCode()
        r = 31 * r + authorSigningKey.contentHashCode(); r = 31 * r + authorNickname.hashCode()
        r = 31 * r + createdAt.hashCode(); r = 31 * r + expiresAt.hashCode()
        r = 31 * r + flags.hashCode(); r = 31 * r + signature.contentHashCode()
        return r
    }

    companion object {
        fun signingBytes(
            postID: ByteArray,
            geohash: String,
            content: String,
            authorSigningKey: ByteArray,
            authorNickname: String,
            createdAt: ULong,
            expiresAt: ULong,
            flags: UByte,
        ): ByteArray {
            val out = ArrayList<Byte>()
            BoardWireEncoding.appendContext(BoardWireConstants.POST_SIGNING_CONTEXT, out)
            postID.forEach(out::add)
            BoardWireEncoding.appendLengthPrefixed(geohash.encodeToByteArray(), out)
            BoardWireEncoding.appendLengthPrefixed(content.encodeToByteArray(), out)
            authorSigningKey.forEach(out::add)
            BoardWireEncoding.appendLengthPrefixed(authorNickname.encodeToByteArray(), out)
            BoardWireEncoding.appendUInt64(createdAt, out)
            BoardWireEncoding.appendUInt64(expiresAt, out)
            out.add(flags.toByte())
            return out.toByteArray()
        }
    }
}

/** A signed deletion marker; only the author's key can produce a valid one. */
class BoardTombstonePacket(
    val postID: ByteArray,
    val authorSigningKey: ByteArray,
    val deletedAt: ULong,
    val signature: ByteArray,
) {
    val signingBytes: ByteArray get() = signingBytes(postID, deletedAt)

    fun verifySignature(verify: (ByteArray, ByteArray, ByteArray) -> Boolean): Boolean =
        signature.size == BoardWireConstants.SIGNATURE_LENGTH && verify(authorSigningKey, signingBytes, signature)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BoardTombstonePacket) return false
        return postID.contentEquals(other.postID) && authorSigningKey.contentEquals(other.authorSigningKey) &&
            deletedAt == other.deletedAt && signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var r = postID.contentHashCode()
        r = 31 * r + authorSigningKey.contentHashCode(); r = 31 * r + deletedAt.hashCode()
        r = 31 * r + signature.contentHashCode()
        return r
    }

    companion object {
        fun signingBytes(postID: ByteArray, deletedAt: ULong): ByteArray {
            val out = ArrayList<Byte>()
            BoardWireEncoding.appendContext(BoardWireConstants.TOMBSTONE_SIGNING_CONTEXT, out)
            postID.forEach(out::add)
            BoardWireEncoding.appendUInt64(deletedAt, out)
            return out.toByteArray()
        }
    }
}

/** Decoded board payload: a live post or a tombstone. */
sealed class BoardWire {
    data class Post(val post: BoardPostPacket) : BoardWire()
    data class Tombstone(val tombstone: BoardTombstonePacket) : BoardWire()

    fun encode(): ByteArray {
        val out = ArrayList<Byte>()
        fun putTlv(type: UByte, value: ByteArray) {
            out.add(type.toByte())
            out.add((value.size ushr 8).toByte())
            out.add(value.size.toByte())
            value.forEach(out::add)
        }
        when (this) {
            is Post -> {
                putTlv(TLV_KIND, byteArrayOf(KIND_POST.toByte()))
                putTlv(TLV_POST_ID, post.postID)
                putTlv(TLV_GEOHASH, post.geohash.encodeToByteArray())
                putTlv(TLV_CONTENT, post.content.encodeToByteArray())
                putTlv(TLV_AUTHOR_KEY, post.authorSigningKey)
                putTlv(TLV_AUTHOR_NICK, post.authorNickname.encodeToByteArray())
                putTlv(TLV_CREATED_AT, BoardWireEncoding.uint64Bytes(post.createdAt))
                putTlv(TLV_EXPIRES_AT, BoardWireEncoding.uint64Bytes(post.expiresAt))
                putTlv(TLV_FLAGS, byteArrayOf(post.flags.toByte()))
                putTlv(TLV_SIGNATURE, post.signature)
            }
            is Tombstone -> {
                putTlv(TLV_KIND, byteArrayOf(KIND_TOMBSTONE.toByte()))
                putTlv(TLV_POST_ID, tombstone.postID)
                putTlv(TLV_AUTHOR_KEY, tombstone.authorSigningKey)
                putTlv(TLV_DELETED_AT, BoardWireEncoding.uint64Bytes(tombstone.deletedAt))
                putTlv(TLV_SIGNATURE, tombstone.signature)
            }
        }
        return out.toByteArray()
    }

    fun verifySignature(verify: (ByteArray, ByteArray, ByteArray) -> Boolean): Boolean = when (this) {
        is Post -> post.verifySignature(verify)
        is Tombstone -> tombstone.verifySignature(verify)
    }

    companion object {
        private val TLV_KIND: UByte = 0x01u
        private val TLV_POST_ID: UByte = 0x02u
        private val TLV_GEOHASH: UByte = 0x03u
        private val TLV_CONTENT: UByte = 0x04u
        private val TLV_AUTHOR_KEY: UByte = 0x05u
        private val TLV_AUTHOR_NICK: UByte = 0x06u
        private val TLV_CREATED_AT: UByte = 0x07u
        private val TLV_EXPIRES_AT: UByte = 0x08u
        private val TLV_FLAGS: UByte = 0x09u
        private val TLV_SIGNATURE: UByte = 0x0Au
        private val TLV_DELETED_AT: UByte = 0x0Bu

        private const val KIND_POST = 0x01
        private const val KIND_TOMBSTONE = 0x02

        /**
         * Structural decode + shape validation. The caller must still verify the signature before
         * ingesting ([verifySignature]). Returns null on malformed framing or an out-of-bounds field.
         */
        fun decode(data: ByteArray): BoardWire? {
            var offset = 0
            var kind: Int? = null
            var postID: ByteArray? = null
            var geohash: String? = null
            var content: String? = null
            var contentBytes = 0
            var authorKey: ByteArray? = null
            var nickname: String? = null
            var nicknameBytes = 0
            var createdAt: ULong? = null
            var expiresAt: ULong? = null
            var flags: UByte? = null
            var signature: ByteArray? = null
            var deletedAt: ULong? = null

            while (offset + 3 <= data.size) {
                val type = data[offset].toUByte()
                val len = ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset + 2].toInt() and 0xFF)
                val valueStart = offset + 3
                val valueEnd = valueStart + len
                if (valueEnd > data.size) return null
                val v = data.copyOfRange(valueStart, valueEnd)
                offset = valueEnd
                when (type) {
                    TLV_KIND -> { if (len != 1) return null; kind = v[0].toInt() and 0xFF }
                    TLV_POST_ID -> { if (len != BoardWireConstants.POST_ID_LENGTH) return null; postID = v }
                    TLV_GEOHASH -> { if (len > BoardWireConstants.GEOHASH_MAX_LENGTH) return null; geohash = v.decodeToString() }
                    TLV_CONTENT -> { if (len > BoardWireConstants.CONTENT_MAX_BYTES) return null; contentBytes = len; content = v.decodeToString() }
                    TLV_AUTHOR_KEY -> { if (len != BoardWireConstants.SIGNING_KEY_LENGTH) return null; authorKey = v }
                    TLV_AUTHOR_NICK -> { if (len > BoardWireConstants.NICKNAME_MAX_BYTES) return null; nicknameBytes = len; nickname = v.decodeToString() }
                    TLV_CREATED_AT -> createdAt = BoardWireEncoding.uint64(v)
                    TLV_EXPIRES_AT -> expiresAt = BoardWireEncoding.uint64(v)
                    TLV_FLAGS -> { if (len != 1) return null; flags = v[0].toUByte() }
                    TLV_SIGNATURE -> { if (len != BoardWireConstants.SIGNATURE_LENGTH) return null; signature = v }
                    TLV_DELETED_AT -> deletedAt = BoardWireEncoding.uint64(v)
                    // Unknown TLV: forward-compatible, ignore.
                }
            }

            if (postID == null || authorKey == null || signature == null) return null

            return when (kind) {
                KIND_POST -> {
                    if (geohash == null || content == null || nickname == null ||
                        createdAt == null || expiresAt == null || flags == null ||
                        contentBytes < 1 || nicknameBytes > BoardWireConstants.NICKNAME_MAX_BYTES ||
                        !isValidGeohash(geohash) || expiresAt <= createdAt ||
                        expiresAt - createdAt > BoardWireConstants.MAX_LIFETIME_MS.toULong()
                    ) {
                        return null
                    }
                    Post(BoardPostPacket(postID, geohash, content, authorKey, nickname, createdAt, expiresAt, flags, signature))
                }
                KIND_TOMBSTONE -> {
                    if (deletedAt == null) return null
                    Tombstone(BoardTombstonePacket(postID, authorKey, deletedAt, signature))
                }
                else -> null
            }
        }

        /** Cheap TLV peek for relay policy: is this payload an urgent post? Avoids a full decode. */
        fun urgentFlag(data: ByteArray): Boolean {
            var offset = 0
            while (offset + 3 <= data.size) {
                val type = data[offset].toUByte()
                val len = ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset + 2].toInt() and 0xFF)
                val valueStart = offset + 3
                if (valueStart + len > data.size) return false
                if (type == TLV_FLAGS && len == 1) {
                    return (data[valueStart].toInt() and BoardWireConstants.URGENT_FLAG) != 0
                }
                offset = valueStart + len
            }
            return false
        }

        private fun isValidGeohash(geohash: String): Boolean =
            geohash.isEmpty() || geohash.all { it in BoardWireConstants.GEOHASH_ALPHABET }
    }
}

object BoardWireEncoding {
    /** Single-byte length-prefixed context string (capped at 255 bytes). */
    fun appendContext(context: String, out: MutableList<Byte>) {
        val bytes = context.encodeToByteArray()
        val take = minOf(bytes.size, 255)
        out.add(take.toByte())
        for (i in 0 until take) out.add(bytes[i])
    }

    /** u16 big-endian length-prefixed value. */
    fun appendLengthPrefixed(value: ByteArray, out: MutableList<Byte>) {
        val len = minOf(value.size, 0xFFFF)
        out.add((len ushr 8).toByte())
        out.add(len.toByte())
        for (i in 0 until len) out.add(value[i])
    }

    fun appendUInt64(value: ULong, out: MutableList<Byte>) {
        for (i in 7 downTo 0) out.add((value shr (8 * i)).toByte())
    }

    fun uint64Bytes(value: ULong): ByteArray = ByteArray(8) { i -> (value shr (8 * (7 - i))).toByte() }

    fun uint64(data: ByteArray): ULong? {
        if (data.size != 8) return null
        return data.fold(0uL) { acc, b -> (acc shl 8) or (b.toUByte().toULong()) }
    }
}
