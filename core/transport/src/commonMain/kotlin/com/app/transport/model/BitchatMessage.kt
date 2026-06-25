@file:UseSerializers(DateSerializer::class)
@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.app.transport.model

import com.app.transport.serialization.DateSerializer
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
enum class BitchatMessageType {
    Message,
    Audio,
    Image,
    File
}

/**
 * Delivery status for messages - exact same as iOS version
 */
@Serializable
sealed class DeliveryStatus {
    @Serializable
    object Sending : DeliveryStatus()

    @Serializable
    object Sent : DeliveryStatus()

    @Serializable
    data class Delivered(val to: String, val at: Instant) : DeliveryStatus()

    @Serializable
    data class Read(val by: String, val at: Instant) : DeliveryStatus()

    @Serializable
    data class Failed(val reason: String) : DeliveryStatus()

    @Serializable
    data class PartiallyDelivered(val reached: Int, val total: Int) : DeliveryStatus()

    fun getDisplayText(): String {
        return when (this) {
            is Sending -> "Sending..."
            is Sent -> "Sent"
            is Delivered -> "Delivered to ${this.to}"
            is Read -> "Read by ${this.by}"
            is Failed -> "Failed: ${this.reason}"
            is PartiallyDelivered -> "Delivered to ${this.reached}/${this.total}"
        }
    }
}

/**
 * BitchatMessage - 100% compatible with iOS version
 */
@Serializable
data class BitchatMessage(
    val id: String = Uuid.random().toString().uppercase(),
    val sender: String,
    val content: String,
    val type: BitchatMessageType = BitchatMessageType.Message,
    val timestamp: Instant,
    val isRelay: Boolean = false,
    val originalSender: String? = null,
    val isPrivate: Boolean = false,
    val recipientNickname: String? = null,
    val senderPeerID: String? = null,
    val mentions: List<String>? = null,
    val channel: String? = null,
    val encryptedContent: ByteArray? = null,
    val isEncrypted: Boolean = false,
    val deliveryStatus: DeliveryStatus? = null,
    val powDifficulty: Int? = null
) {

    /**
     * Convert message to binary payload format - exactly same as iOS version
     */
    fun toBinaryPayload(): ByteArray? {
        try {
            // kotlinx-io Buffer writes big-endian by default, matching the iOS wire (BIG_ENDIAN).
            val buffer = Buffer()

            // Message format:
            // - Flags: 1 byte (bit flags for optional fields)
            // - Timestamp: 8 bytes (milliseconds since epoch, big-endian)
            // - ID length: 1 byte + ID data
            // - Sender length: 1 byte + sender data
            // - Content length: 2 bytes + content data (or encrypted content)
            // Optional fields based on flags...

            var flags: UByte = 0u
            if (isRelay) flags = flags or 0x01u
            if (isPrivate) flags = flags or 0x02u
            if (originalSender != null) flags = flags or 0x04u
            if (recipientNickname != null) flags = flags or 0x08u
            if (senderPeerID != null) flags = flags or 0x10u
            if (mentions != null && mentions.isNotEmpty()) flags = flags or 0x20u
            if (channel != null) flags = flags or 0x40u
            if (isEncrypted) flags = flags or 0x80u

            buffer.writeByte(flags.toByte())

            // Timestamp (in milliseconds, 8 bytes big-endian)
            val timestampMillis = timestamp.toEpochMilliseconds()
            buffer.writeLong(timestampMillis)

            // ID
            val idBytes = id.encodeToByteArray()
            buffer.writeByte(minOf(idBytes.size, 255).toByte())
            buffer.write(idBytes.take(255).toByteArray())

            // Sender
            val senderBytes = sender.encodeToByteArray()
            buffer.writeByte(minOf(senderBytes.size, 255).toByte())
            buffer.write(senderBytes.take(255).toByteArray())

            // Content or encrypted content
            if (isEncrypted && encryptedContent != null) {
                val length = minOf(encryptedContent.size, 65535)
                buffer.writeShort(length.toShort())
                buffer.write(encryptedContent.take(length).toByteArray())
            } else {
                val contentBytes = content.encodeToByteArray()
                val length = minOf(contentBytes.size, 65535)
                buffer.writeShort(length.toShort())
                buffer.write(contentBytes.take(length).toByteArray())
            }

            // Optional fields
            originalSender?.let { origSender ->
                val origBytes = origSender.encodeToByteArray()
                buffer.writeByte(minOf(origBytes.size, 255).toByte())
                buffer.write(origBytes.take(255).toByteArray())
            }

            recipientNickname?.let { recipient ->
                val recipBytes = recipient.encodeToByteArray()
                buffer.writeByte(minOf(recipBytes.size, 255).toByte())
                buffer.write(recipBytes.take(255).toByteArray())
            }

            senderPeerID?.let { peerID ->
                val peerBytes = peerID.encodeToByteArray()
                buffer.writeByte(minOf(peerBytes.size, 255).toByte())
                buffer.write(peerBytes.take(255).toByteArray())
            }

            // Mentions array
            mentions?.let { mentionList ->
                buffer.writeByte(minOf(mentionList.size, 255).toByte())
                mentionList.take(255).forEach { mention ->
                    val mentionBytes = mention.encodeToByteArray()
                    buffer.writeByte(minOf(mentionBytes.size, 255).toByte())
                    buffer.write(mentionBytes.take(255).toByteArray())
                }
            }

            // Channel hashtag
            channel?.let { channelName ->
                val channelBytes = channelName.encodeToByteArray()
                buffer.writeByte(minOf(channelBytes.size, 255).toByte())
                buffer.write(channelBytes.take(255).toByteArray())
            }

            return buffer.readByteArray()

        } catch (e: Exception) {
            return null
        }
    }

    companion object {
        /**
         * Parse message from binary payload - exactly same logic as iOS version
         */
        fun fromBinaryPayload(data: ByteArray): BitchatMessage? {
            try {
                if (data.size < 13) return null

                val buffer = Buffer().apply { write(data) }

                // Flags
                val flags = buffer.readByte().toUByte()
                val isRelay = (flags and 0x01u) != 0u.toUByte()
                val isPrivate = (flags and 0x02u) != 0u.toUByte()
                val hasOriginalSender = (flags and 0x04u) != 0u.toUByte()
                val hasRecipientNickname = (flags and 0x08u) != 0u.toUByte()
                val hasSenderPeerID = (flags and 0x10u) != 0u.toUByte()
                val hasMentions = (flags and 0x20u) != 0u.toUByte()
                val hasChannel = (flags and 0x40u) != 0u.toUByte()
                val isEncrypted = (flags and 0x80u) != 0u.toUByte()

                // Timestamp
                val timestampMillis = buffer.readLong()
                val timestamp = Instant.fromEpochMilliseconds(timestampMillis)

                // ID
                val idLength = buffer.readByte().toInt() and 0xFF
                if (buffer.size < idLength) return null
                val id = buffer.readByteArray(idLength).decodeToString()

                // Sender
                val senderLength = buffer.readByte().toInt() and 0xFF
                if (buffer.size < senderLength) return null
                val sender = buffer.readByteArray(senderLength).decodeToString()

                // Content
                val contentLength = buffer.readShort().toInt() and 0xFFFF
                if (buffer.size < contentLength) return null

                val content: String
                val encryptedContent: ByteArray?

                if (isEncrypted) {
                    encryptedContent = buffer.readByteArray(contentLength)
                    content = "" // Empty placeholder
                } else {
                    content = buffer.readByteArray(contentLength).decodeToString()
                    encryptedContent = null
                }

                // Optional fields
                val originalSender = if (hasOriginalSender && !buffer.exhausted()) {
                    val length = buffer.readByte().toInt() and 0xFF
                    if (buffer.size >= length) buffer.readByteArray(length).decodeToString() else null
                } else null

                val recipientNickname = if (hasRecipientNickname && !buffer.exhausted()) {
                    val length = buffer.readByte().toInt() and 0xFF
                    if (buffer.size >= length) buffer.readByteArray(length).decodeToString() else null
                } else null

                val senderPeerID = if (hasSenderPeerID && !buffer.exhausted()) {
                    val length = buffer.readByte().toInt() and 0xFF
                    if (buffer.size >= length) buffer.readByteArray(length).decodeToString() else null
                } else null

                // Mentions array
                val mentions = if (hasMentions && !buffer.exhausted()) {
                    val mentionCount = buffer.readByte().toInt() and 0xFF
                    val mentionList = mutableListOf<String>()
                    repeat(mentionCount) {
                        if (!buffer.exhausted()) {
                            val length = buffer.readByte().toInt() and 0xFF
                            if (buffer.size >= length) {
                                mentionList.add(buffer.readByteArray(length).decodeToString())
                            }
                        }
                    }
                    if (mentionList.isNotEmpty()) mentionList else null
                } else null

                // Channel
                val channel = if (hasChannel && !buffer.exhausted()) {
                    val length = buffer.readByte().toInt() and 0xFF
                    if (buffer.size >= length) buffer.readByteArray(length).decodeToString() else null
                } else null

                return BitchatMessage(
                    id = id,
                    sender = sender,
                    content = content,
                    type = BitchatMessageType.Message,
                    timestamp = timestamp,
                    isRelay = isRelay,
                    originalSender = originalSender,
                    isPrivate = isPrivate,
                    recipientNickname = recipientNickname,
                    senderPeerID = senderPeerID,
                    mentions = mentions,
                    channel = channel,
                    encryptedContent = encryptedContent,
                    isEncrypted = isEncrypted
                )

            } catch (e: Exception) {
                return null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as BitchatMessage

        if (id != other.id) return false
        if (sender != other.sender) return false
        if (content != other.content) return false
        if (type != other.type) return false
        if (timestamp != other.timestamp) return false
        if (isRelay != other.isRelay) return false
        if (originalSender != other.originalSender) return false
        if (isPrivate != other.isPrivate) return false
        if (recipientNickname != other.recipientNickname) return false
        if (senderPeerID != other.senderPeerID) return false
        if (mentions != other.mentions) return false
        if (channel != other.channel) return false
        if (encryptedContent != null) {
            if (other.encryptedContent == null) return false
            if (!encryptedContent.contentEquals(other.encryptedContent)) return false
        } else if (other.encryptedContent != null) return false
        if (isEncrypted != other.isEncrypted) return false
        if (deliveryStatus != other.deliveryStatus) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sender.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isRelay.hashCode()
        result = 31 * result + (originalSender?.hashCode() ?: 0)
        result = 31 * result + isPrivate.hashCode()
        result = 31 * result + (recipientNickname?.hashCode() ?: 0)
        result = 31 * result + (senderPeerID?.hashCode() ?: 0)
        result = 31 * result + (mentions?.hashCode() ?: 0)
        result = 31 * result + (channel?.hashCode() ?: 0)
        result = 31 * result + (encryptedContent?.contentHashCode() ?: 0)
        result = 31 * result + isEncrypted.hashCode()
        result = 31 * result + (deliveryStatus?.hashCode() ?: 0)
        return result
    }
}
