package com.app.transport.model

import com.app.common.encoding.hexEncodedString
import kotlinx.serialization.Serializable

/**
 * Identity announcement structure with TLV encoding
 * Compatible with iOS AnnouncementPacket TLV format
 *
 * [capabilities] is null when the peer predates the 0x05 TLV; the reference collapses that to the
 * empty set at its peer registry, so absent and empty carry the same meaning downstream.
 */
@Serializable
data class IdentityAnnouncement(
    val nickname: String,
    val noisePublicKey: ByteArray,    // Noise static public key (Curve25519.KeyAgreement)
    val signingPublicKey: ByteArray,  // Ed25519 public key for signing
    val capabilities: PeerCapabilities? = null,  // advertised feature bits; null when absent
) {

    /**
     * TLV types matching iOS implementation.
     *
     * 0x04 (directNeighbors) is absent on purpose: it is appended and parsed outside this codec by
     * the mesh layer's gossip TLV helper, and this decoder skips it as an unknown type.
     */
    private enum class TLVType(val value: UByte) {
        NICKNAME(0x01u),
        NOISE_PUBLIC_KEY(0x02u),
        SIGNING_PUBLIC_KEY(0x03u),  // NEW: Ed25519 signing public key
        CAPABILITIES(0x05u);        // advertised feature bits (PeerCapabilities)

        companion object {
            fun fromValue(value: UByte): TLVType? {
                return entries.find { it.value == value }
            }
        }
    }

    /**
     * Encode to TLV binary data matching iOS implementation.
     *
     * The capabilities TLV is emitted right after 0x03, so when the mesh layer appends its gossip
     * neighbors TLV the field order reads 0x01,0x02,0x03,0x05,0x04 — the reference emits 0x04
     * before 0x05. Both decoders walk TLVs in a type-switched loop and neither depends on order,
     * and the packet signature covers whatever bytes we actually emit, so the difference is inert.
     */
    fun encode(): ByteArray? {
        val nicknameData = nickname.encodeToByteArray()

        // Check size limits
        if (nicknameData.size > 255 || noisePublicKey.size > 255 || signingPublicKey.size > 255) {
            return null
        }

        val result = mutableListOf<Byte>()

        // TLV for nickname
        result.add(TLVType.NICKNAME.value.toByte())
        result.add(nicknameData.size.toByte())
        result.addAll(nicknameData.toList())

        // TLV for noise public key
        result.add(TLVType.NOISE_PUBLIC_KEY.value.toByte())
        result.add(noisePublicKey.size.toByte())
        result.addAll(noisePublicKey.toList())

        // TLV for signing public key
        result.add(TLVType.SIGNING_PUBLIC_KEY.value.toByte())
        result.add(signingPublicKey.size.toByte())
        result.addAll(signingPublicKey.toList())

        // TLV for capabilities (optional; omitted entirely when we advertise nothing)
        capabilities?.let { caps ->
            val capabilityBytes = caps.encoded()
            if (capabilityBytes.size > 255) return null
            result.add(TLVType.CAPABILITIES.value.toByte())
            result.add(capabilityBytes.size.toByte())
            result.addAll(capabilityBytes.toList())
        }

        return result.toByteArray()
    }
    
    companion object {
        /**
         * Decode from TLV binary data matching iOS implementation
         */
        fun decode(data: ByteArray): IdentityAnnouncement? {
            // Create defensive copy
            val dataCopy = data.copyOf()
            
            var offset = 0
            var nickname: String? = null
            var noisePublicKey: ByteArray? = null
            var signingPublicKey: ByteArray? = null
            var capabilities: PeerCapabilities? = null

            while (offset + 2 <= dataCopy.size) {
                // Read TLV type
                val typeValue = dataCopy[offset].toUByte()
                val type = TLVType.fromValue(typeValue)
                offset += 1
                
                // Read TLV length
                val length = dataCopy[offset].toUByte().toInt()
                offset += 1
                
                // Check bounds
                if (offset + length > dataCopy.size) return null
                
                // Read TLV value
                val value = dataCopy.sliceArray(offset until offset + length)
                offset += length
                
                // Process known TLV types, skip unknown ones for forward compatibility
                when (type) {
                    TLVType.NICKNAME -> {
                        nickname = value.decodeToString()
                    }
                    TLVType.NOISE_PUBLIC_KEY -> {
                        noisePublicKey = value
                    }
                    TLVType.SIGNING_PUBLIC_KEY -> {
                        signingPublicKey = value
                    }
                    TLVType.CAPABILITIES -> {
                        capabilities = PeerCapabilities.decode(value)
                    }
                    null -> {
                        // Unknown TLV; skip (tolerant decoder for forward compatibility)
                        continue
                    }
                }
            }

            // All three identity fields are required; capabilities stay optional
            return if (nickname != null && noisePublicKey != null && signingPublicKey != null) {
                IdentityAnnouncement(nickname, noisePublicKey, signingPublicKey, capabilities)
            } else {
                null
            }
        }
    }
    
    // Override equals and hashCode since we use ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as IdentityAnnouncement
        
        if (nickname != other.nickname) return false
        if (!noisePublicKey.contentEquals(other.noisePublicKey)) return false
        if (!signingPublicKey.contentEquals(other.signingPublicKey)) return false
        if (capabilities != other.capabilities) return false

        return true
    }

    override fun hashCode(): Int {
        var result = nickname.hashCode()
        result = 31 * result + noisePublicKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        result = 31 * result + (capabilities?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "IdentityAnnouncement(nickname='$nickname', noisePublicKey=${noisePublicKey.hexEncodedString().take(16)}..., signingPublicKey=${signingPublicKey.hexEncodedString().take(16)}..., capabilities=$capabilities)"
    }
}
