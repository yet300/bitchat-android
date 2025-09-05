package com.bitchat.data.dto

import android.os.Parcelable
import com.bitchat.domain.model.NoisePayloadType
import kotlinx.parcelize.Parcelize

/**
 * DTO for NoiseEncrypted models with Parcelize support for Android
 */

/**
 * DTO for NoisePayload with Parcelize support
 */
@Parcelize
data class NoisePayloadDto(
    val type: NoisePayloadType,
    val data: ByteArray
) : Parcelable {

    // Override equals and hashCode since we use ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as NoisePayloadDto
        
        if (type != other.type) return false
        if (!data.contentEquals(other.data)) return false
        
        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * DTO for PrivateMessagePacket with Parcelize support
 */
@Parcelize
data class PrivateMessagePacketDto(
    val messageID: String,
    val content: String
) : Parcelable {
    
    override fun toString(): String {
        return "PrivateMessagePacketDto(messageID='$messageID', content='${content.take(50)}${if (content.length > 50) "..." else ""}')"
    }
}

/**
 * DTO for ReadReceipt with Parcelize support
 */
@Parcelize
data class ReadReceiptDto(
    val originalMessageID: String,
    val readerPeerID: String? = null
) : Parcelable
