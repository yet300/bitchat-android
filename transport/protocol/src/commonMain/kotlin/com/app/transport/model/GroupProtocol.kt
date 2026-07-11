package com.app.transport.model

import com.app.common.encoding.hexEncodedString
import com.app.transport.crypto.Sha256

/**
 * Wire formats for private groups — byte-for-byte compatible with the reference iOS
 * `Services/Groups/GroupProtocol.swift`.
 *
 * Two wire surfaces:
 * - [GroupStatePayload]: creator-signed group state (roster + current-epoch key), carried 1:1 over
 *   an authenticated Noise session as `NoisePayloadType.GROUP_INVITE (0x06)` /
 *   `GROUP_KEY_UPDATE (0x07)`.
 * - [GroupMessageEnvelope]: a ChaCha20-Poly1305 group broadcast, `MessageType.GROUP_MESSAGE (0x25)`.
 *   Only the cleartext group ID, epoch, and nonce are visible to relays; everything about the
 *   message rides inside the ciphertext (see [GroupCrypto]).
 *
 * Fingerprints are stored as the raw 32-byte SHA-256 of a Noise static key (exposed as hex via
 * [GroupMember.fingerprintHex]), mirroring [VouchAttestation]. Signature verification and sealing
 * live in [GroupCrypto]; the codecs here are pure so they stay golden-testable without crypto keys.
 */

/** A member of a private group as pinned in the creator-signed roster. */
class GroupMember(
    /** Raw 32-byte SHA-256 fingerprint of the member's Noise static key. */
    val fingerprint: ByteArray,
    /** The member's Ed25519 signing public key (32 bytes, from their announce). */
    val signingKey: ByteArray,
    /** Nickname at invite time; display fallback when the peer is offline. */
    val nickname: String,
) {
    val fingerprintHex: String get() = fingerprint.hexEncodedString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupMember) return false
        return fingerprint.contentEquals(other.fingerprint) &&
            signingKey.contentEquals(other.signingKey) &&
            nickname == other.nickname
    }

    override fun hashCode(): Int {
        var result = fingerprint.contentHashCode()
        result = 31 * result + signingKey.contentHashCode()
        result = 31 * result + nickname.hashCode()
        return result
    }
}

/**
 * Creator-managed encrypted group (metadata + current-epoch key). [epoch] bumps on every key
 * rotation; messages are bound to the epoch they were sealed under.
 */
class BitchatGroup(
    /** 16 random bytes; travels in cleartext on group message packets so relays can dedup. */
    val groupID: ByteArray,
    val name: String,
    val epoch: UInt,
    val members: List<GroupMember>,
    /** Raw 32-byte fingerprint of the creator — the only identity allowed to sign group state. */
    val creatorFingerprint: ByteArray,
) {
    val creatorFingerprintHex: String get() = creatorFingerprint.hexEncodedString()

    val creator: GroupMember?
        get() = members.firstOrNull { it.fingerprint.contentEquals(creatorFingerprint) }

    fun isMember(fingerprint: ByteArray): Boolean =
        members.any { it.fingerprint.contentEquals(fingerprint) }

    fun member(signingKey: ByteArray): GroupMember? =
        members.firstOrNull { it.signingKey.contentEquals(signingKey) }

    companion object {
        const val MAX_MEMBERS = 16
        const val GROUP_ID_LENGTH = 16
        const val KEY_LENGTH = 32
        const val FINGERPRINT_LENGTH = 32
        const val SIGNING_KEY_LENGTH = 32
        const val SIGNATURE_LENGTH = 64
    }
}

/**
 * TLV helpers shared by the group wire forms: `type(1B) | length(2B big-endian) | value`. Encoding
 * returns null rather than truncating when a value overflows the 16-bit length field, so an oversize
 * field surfaces a send failure instead of a silently truncated blob the recipient rejects.
 */
internal object GroupTlv {

    fun put(type: UByte, value: ByteArray, out: MutableList<Byte>): Boolean {
        if (value.size > 0xFFFF) return false
        out.add(type.toByte())
        out.add((value.size ushr 8).toByte())
        out.add(value.size.toByte())
        value.forEach(out::add)
        return true
    }

    /** Iterates (type, value) pairs; returns null on malformed framing. */
    fun parse(data: ByteArray): List<Pair<UByte, ByteArray>>? {
        val fields = ArrayList<Pair<UByte, ByteArray>>()
        var offset = 0
        while (offset < data.size) {
            if (offset + 3 > data.size) return null
            val type = data[offset].toUByte()
            val length = ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset + 2].toInt() and 0xFF)
            val valueStart = offset + 3
            val valueEnd = valueStart + length
            if (valueEnd > data.size) return null
            fields.add(type to data.copyOfRange(valueStart, valueEnd))
            offset = valueEnd
        }
        return fields
    }

    fun epochBytes(epoch: UInt): ByteArray = ByteArray(4) { i -> (epoch shr (8 * (3 - i))).toByte() }

    fun epoch(from: ByteArray): UInt? {
        if (from.size != 4) return null
        return from.fold(0u) { acc, b -> (acc shl 8) or (b.toUByte().toUInt()) }
    }

    fun timestampBytes(ms: ULong): ByteArray = ByteArray(8) { i -> (ms shr (8 * (7 - i))).toByte() }

    fun timestamp(from: ByteArray): ULong? {
        if (from.size != 8) return null
        return from.fold(0uL) { acc, b -> (acc shl 8) or (b.toUByte().toULong()) }
    }
}

/**
 * Deterministic roster blob the creator signature covers by hash: `count(1B)` then per member the
 * raw 32-byte fingerprint, 32-byte signing key, and a `len(1B)`-prefixed UTF-8 nickname
 * (truncated to [MAX_NICKNAME_BYTES] on a UTF-8 boundary).
 */
object GroupRosterCoding {

    private const val MAX_NICKNAME_BYTES = 64

    fun encode(members: List<GroupMember>): ByteArray? {
        if (members.size > BitchatGroup.MAX_MEMBERS) return null
        val out = ArrayList<Byte>()
        out.add(members.size.toByte())
        for (member in members) {
            if (member.fingerprint.size != BitchatGroup.FINGERPRINT_LENGTH ||
                member.signingKey.size != BitchatGroup.SIGNING_KEY_LENGTH
            ) {
                return null
            }
            member.fingerprint.forEach(out::add)
            member.signingKey.forEach(out::add)
            val nickname = truncatedNickname(member.nickname)
            out.add(nickname.size.toByte())
            nickname.forEach(out::add)
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): List<GroupMember>? {
        if (data.isEmpty()) return null
        val count = data[0].toUByte().toInt()
        if (count > BitchatGroup.MAX_MEMBERS) return null
        val members = ArrayList<GroupMember>(count)
        var offset = 1
        repeat(count) {
            val fixed = BitchatGroup.FINGERPRINT_LENGTH + BitchatGroup.SIGNING_KEY_LENGTH + 1
            if (offset + fixed > data.size) return null
            val fingerprint = data.copyOfRange(offset, offset + BitchatGroup.FINGERPRINT_LENGTH)
            val signingStart = offset + BitchatGroup.FINGERPRINT_LENGTH
            val signingKey = data.copyOfRange(signingStart, signingStart + BitchatGroup.SIGNING_KEY_LENGTH)
            val nickLenIndex = signingStart + BitchatGroup.SIGNING_KEY_LENGTH
            val nickLength = data[nickLenIndex].toUByte().toInt()
            val nickStart = nickLenIndex + 1
            val nickEnd = nickStart + nickLength
            if (nickEnd > data.size) return null
            val nickname = data.copyOfRange(nickStart, nickEnd).decodeToString()
            members.add(GroupMember(fingerprint, signingKey, nickname))
            offset = nickEnd
        }
        if (offset != data.size) return null
        return members
    }

    /**
     * UTF-8 bytes of [nickname] trimmed to at most [MAX_NICKNAME_BYTES], cutting on a UTF-8 lead-byte
     * boundary so the result is never split mid-scalar.
     */
    private fun truncatedNickname(nickname: String): ByteArray {
        val bytes = nickname.encodeToByteArray()
        if (bytes.size <= MAX_NICKNAME_BYTES) return bytes
        var end = MAX_NICKNAME_BYTES
        // Back up while the first dropped byte is a UTF-8 continuation byte (0b10xxxxxx).
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOfRange(0, end)
    }
}

/**
 * Creator-signed group state (invite `0x06` / key update `0x07`). Receivers verify the creator
 * signature — over `"bitchat-group-v1" | groupID | epoch | SHA256(key) | SHA256(roster) |
 * SHA256(name)` — against the creator's signing key pinned in the roster, and require the Noise
 * session peer to BE the creator before accepting any state (that check is the coordinator's).
 */
class GroupStatePayload(
    val groupID: ByteArray,
    val name: String,
    /** Symmetric ChaCha20-Poly1305 group key (32 bytes) for [epoch]. */
    val key: ByteArray,
    val epoch: UInt,
    val members: List<GroupMember>,
    val creatorFingerprint: ByteArray,
    /** Ed25519 signature by the creator. */
    val signature: ByteArray,
) {
    fun encode(): ByteArray? {
        val rosterBlob = GroupRosterCoding.encode(members) ?: return null
        if (creatorFingerprint.size != BitchatGroup.FINGERPRINT_LENGTH) return null
        val out = ArrayList<Byte>()
        if (!GroupTlv.put(FIELD_GROUP_ID, groupID, out)) return null
        if (!GroupTlv.put(FIELD_NAME, name.encodeToByteArray(), out)) return null
        if (!GroupTlv.put(FIELD_KEY, key, out)) return null
        if (!GroupTlv.put(FIELD_EPOCH, GroupTlv.epochBytes(epoch), out)) return null
        if (!GroupTlv.put(FIELD_ROSTER, rosterBlob, out)) return null
        if (!GroupTlv.put(FIELD_CREATOR_FINGERPRINT, creatorFingerprint, out)) return null
        if (!GroupTlv.put(FIELD_SIGNATURE, signature, out)) return null
        return out.toByteArray()
    }

    /**
     * Verifies the creator signature against the creator's signing key pinned in the roster, and
     * that the creator is actually in the roster. [verify] is `(publicKey, data, signature) ->
     * Boolean` (Ed25519), injected so this pure module stays crypto-key-free.
     */
    fun verifyCreatorSignature(verify: (ByteArray, ByteArray, ByteArray) -> Boolean): Boolean {
        if (members.size > BitchatGroup.MAX_MEMBERS) return false
        val creator = members.firstOrNull { it.fingerprint.contentEquals(creatorFingerprint) } ?: return false
        val rosterBlob = GroupRosterCoding.encode(members) ?: return false
        val content = signingContent(groupID, epoch, key, rosterBlob, name)
        return verify(creator.signingKey, content, signature)
    }

    fun asGroup(): BitchatGroup =
        BitchatGroup(groupID, name, epoch, members, creatorFingerprint)

    companion object {
        private val FIELD_GROUP_ID: UByte = 0x01u
        private val FIELD_NAME: UByte = 0x02u
        private val FIELD_KEY: UByte = 0x03u
        private val FIELD_EPOCH: UByte = 0x04u
        private val FIELD_ROSTER: UByte = 0x05u
        private val FIELD_CREATOR_FINGERPRINT: UByte = 0x06u
        private val FIELD_SIGNATURE: UByte = 0x07u

        val SIGNING_DOMAIN = "bitchat-group-v1".encodeToByteArray()

        /**
         * The bytes the creator signs. Binding key/roster/name by hash keeps the signed content
         * fixed-size; covering the name stops a caching/replaying relay swapping the display name
         * under a valid signature.
         */
        fun signingContent(groupID: ByteArray, epoch: UInt, key: ByteArray, rosterBlob: ByteArray, name: String): ByteArray {
            val out = ArrayList<Byte>()
            SIGNING_DOMAIN.forEach(out::add)
            groupID.forEach(out::add)
            GroupTlv.epochBytes(epoch).forEach(out::add)
            Sha256.digest(key).forEach(out::add)
            Sha256.digest(rosterBlob).forEach(out::add)
            Sha256.digest(name.encodeToByteArray()).forEach(out::add)
            return out.toByteArray()
        }

        /**
         * Builds a signed state payload. Returns null when the roster cannot be encoded or [sign]
         * (Ed25519 over our creator key) fails.
         */
        fun makeSigned(group: BitchatGroup, key: ByteArray, sign: (ByteArray) -> ByteArray?): GroupStatePayload? {
            val rosterBlob = GroupRosterCoding.encode(group.members) ?: return null
            val content = signingContent(group.groupID, group.epoch, key, rosterBlob, group.name)
            val signature = sign(content) ?: return null
            return GroupStatePayload(
                groupID = group.groupID,
                name = group.name,
                key = key,
                epoch = group.epoch,
                members = group.members,
                creatorFingerprint = group.creatorFingerprint,
                signature = signature,
            )
        }

        fun decode(data: ByteArray): GroupStatePayload? {
            val fields = GroupTlv.parse(data) ?: return null
            var groupID: ByteArray? = null
            var name: String? = null
            var key: ByteArray? = null
            var epoch: UInt? = null
            var members: List<GroupMember>? = null
            var rosterSeen = false
            var creatorFingerprint: ByteArray? = null
            var signature: ByteArray? = null

            for ((type, value) in fields) {
                when (type) {
                    FIELD_GROUP_ID -> if (value.size == BitchatGroup.GROUP_ID_LENGTH) groupID = value
                    FIELD_NAME -> name = value.decodeToString()
                    FIELD_KEY -> if (value.size == BitchatGroup.KEY_LENGTH) key = value
                    FIELD_EPOCH -> epoch = GroupTlv.epoch(value)
                    FIELD_ROSTER -> {
                        rosterSeen = true
                        members = GroupRosterCoding.decode(value)
                    }
                    FIELD_CREATOR_FINGERPRINT -> if (value.size == BitchatGroup.FINGERPRINT_LENGTH) creatorFingerprint = value
                    FIELD_SIGNATURE -> if (value.size == BitchatGroup.SIGNATURE_LENGTH) signature = value
                    // Unknown TLV: forward-compatible, ignore.
                }
            }

            val resolvedMembers = members
            if (groupID == null || name == null || key == null || epoch == null ||
                !rosterSeen || resolvedMembers == null || resolvedMembers.isEmpty() ||
                creatorFingerprint == null || signature == null
            ) {
                return null
            }
            return GroupStatePayload(groupID, name, key, epoch, resolvedMembers, creatorFingerprint, signature)
        }
    }
}

/**
 * Cleartext framing of a group message broadcast (`MessageType.GROUP_MESSAGE = 0x25` payload). Only
 * the group ID, epoch, and nonce are visible to relays; sender/content/timestamps live inside the
 * ChaCha20-Poly1305 [ciphertext] (which carries its 16-byte tag appended).
 */
class GroupMessageEnvelope(
    val groupID: ByteArray,
    val epoch: UInt,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    fun encode(): ByteArray? {
        val out = ArrayList<Byte>()
        if (!GroupTlv.put(FIELD_GROUP_ID, groupID, out)) return null
        if (!GroupTlv.put(FIELD_EPOCH, GroupTlv.epochBytes(epoch), out)) return null
        if (!GroupTlv.put(FIELD_NONCE, nonce, out)) return null
        if (!GroupTlv.put(FIELD_CIPHERTEXT, ciphertext, out)) return null
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupMessageEnvelope) return false
        return groupID.contentEquals(other.groupID) &&
            epoch == other.epoch &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = groupID.contentHashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    companion object {
        const val NONCE_LENGTH = 12

        private val FIELD_GROUP_ID: UByte = 0x01u
        private val FIELD_EPOCH: UByte = 0x02u
        private val FIELD_NONCE: UByte = 0x03u
        private val FIELD_CIPHERTEXT: UByte = 0x04u

        fun decode(data: ByteArray): GroupMessageEnvelope? {
            val fields = GroupTlv.parse(data) ?: return null
            var groupID: ByteArray? = null
            var epoch: UInt? = null
            var nonce: ByteArray? = null
            var ciphertext: ByteArray? = null
            for ((type, value) in fields) {
                when (type) {
                    FIELD_GROUP_ID -> if (value.size == BitchatGroup.GROUP_ID_LENGTH) groupID = value
                    FIELD_EPOCH -> epoch = GroupTlv.epoch(value)
                    FIELD_NONCE -> if (value.size == NONCE_LENGTH) nonce = value
                    FIELD_CIPHERTEXT -> if (value.isNotEmpty()) ciphertext = value
                    // Unknown TLV: ignore.
                }
            }
            if (groupID == null || epoch == null || nonce == null || ciphertext == null) return null
            return GroupMessageEnvelope(groupID, epoch, nonce, ciphertext)
        }
    }
}

/** Decrypted, signature-verified inner content of a group message. */
class GroupMessagePlaintext(
    val messageID: String,
    val senderSigningKey: ByteArray,
    val senderNickname: String,
    val timestampMs: ULong,
    val content: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupMessagePlaintext) return false
        return messageID == other.messageID &&
            senderSigningKey.contentEquals(other.senderSigningKey) &&
            senderNickname == other.senderNickname &&
            timestampMs == other.timestampMs &&
            content == other.content
    }

    override fun hashCode(): Int {
        var result = messageID.hashCode()
        result = 31 * result + senderSigningKey.contentHashCode()
        result = 31 * result + senderNickname.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + content.hashCode()
        return result
    }
}
