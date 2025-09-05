package com.bitchat.data.dto

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.*

/**
 * DTO for BitchatMessage with Parcelize support for Android
 */
@Parcelize
data class BitchatMessageDto(
    val id: String,
    val sender: String,
    val content: String,
    val timestamp: Date,
    val isRelay: Boolean = false,
    val originalSender: String? = null,
    val isPrivate: Boolean = false,
    val recipientNickname: String? = null,
    val senderPeerID: String? = null,
    val mentions: List<String>? = null,
    val channel: String? = null,
    val encryptedContent: ByteArray? = null,
    val isEncrypted: Boolean = false,
    val deliveryStatus: DeliveryStatusDto? = null,
    val powDifficulty: Int? = null
) : Parcelable {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BitchatMessageDto

        if (id != other.id) return false
        if (sender != other.sender) return false
        if (content != other.content) return false
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

/**
 * DTO for DeliveryStatus with Parcelize support
 */
sealed class DeliveryStatusDto : Parcelable {
    @Parcelize
    object Sending : DeliveryStatusDto()

    @Parcelize
    object Sent : DeliveryStatusDto()

    @Parcelize
    data class Delivered(val to: String, val at: Date) : DeliveryStatusDto()

    @Parcelize
    data class Read(val by: String, val at: Date) : DeliveryStatusDto()

    @Parcelize
    data class Failed(val reason: String) : DeliveryStatusDto()

    @Parcelize
    data class PartiallyDelivered(val reached: Int, val total: Int) : DeliveryStatusDto()
}
