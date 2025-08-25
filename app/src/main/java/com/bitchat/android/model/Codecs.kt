package com.bitchat.android.model

// Adapter codecs that operate on domain models; keep Android-free logic here

// Typealiases to domain
typealias NoisePayloadType = com.bitchat.domain.model.NoisePayloadType
typealias NoisePayload = com.bitchat.domain.model.NoisePayload
typealias PrivateMessagePacket = com.bitchat.domain.model.PrivateMessagePacket
typealias IdentityAnnouncement = com.bitchat.domain.model.IdentityAnnouncement
typealias FragmentPayload = com.bitchat.domain.model.FragmentPayload

// NoisePayload encode/decode
fun NoisePayload.encode(): ByteArray {
    val result = ByteArray(1 + data.size)
    result[0] = type.value.toByte()
    data.copyInto(result, destinationOffset = 1)
    return result
}

fun decodeNoisePayload(data: ByteArray): NoisePayload? {
    if (data.isEmpty()) return null
    val typeValue = data[0].toUByte()
    val type = NoisePayloadType.entries.find { it.value == typeValue } ?: return null
    val payload = if (data.size > 1) data.copyOfRange(1, data.size) else ByteArray(0)
    return NoisePayload(type, payload)
}

// PrivateMessagePacket TLV encode/decode
private enum class PMTLV(val value: UByte) { MESSAGE_ID(0x00u), CONTENT(0x01u) }

fun encodePrivateMessagePacket(messageID: String, content: String): ByteArray? {
    val messageIDData = messageID.toByteArray(Charsets.UTF_8)
    val contentData = content.toByteArray(Charsets.UTF_8)
    if (messageIDData.size > 255 || contentData.size > 255) return null
    val result = mutableListOf<Byte>()
    result.add(PMTLV.MESSAGE_ID.value.toByte()); result.add(messageIDData.size.toByte()); result.addAll(messageIDData.toList())
    result.add(PMTLV.CONTENT.value.toByte()); result.add(contentData.size.toByte()); result.addAll(contentData.toList())
    return result.toByteArray()
}

fun decodePrivateMessagePacket(data: ByteArray): PrivateMessagePacket? {
    var offset = 0
    var messageID: String? = null
    var content: String? = null
    while (offset + 2 <= data.size) {
        val typeValue = data[offset].toUByte(); offset += 1
        val length = data[offset].toUByte().toInt(); offset += 1
        if (offset + length > data.size) return null
        val value = data.copyOfRange(offset, offset + length); offset += length
        when (typeValue) {
            PMTLV.MESSAGE_ID.value -> messageID = String(value, Charsets.UTF_8)
            PMTLV.CONTENT.value -> content = String(value, Charsets.UTF_8)
            else -> return null
        }
    }
    return if (messageID != null && content != null) PrivateMessagePacket(messageID!!, content!!) else null
}

// IdentityAnnouncement TLV encode/decode
private enum class TLVType(val value: UByte) { NICKNAME(0x01u), NOISE_PUBLIC_KEY(0x02u), SIGNING_PUBLIC_KEY(0x03u) }

fun IdentityAnnouncement.encode(): ByteArray? {
    val nicknameData = nickname.toByteArray(Charsets.UTF_8)
    if (nicknameData.size > 255 || noisePublicKey.size > 255 || signingPublicKey.size > 255) return null
    val result = mutableListOf<Byte>()
    result.add(TLVType.NICKNAME.value.toByte()); result.add(nicknameData.size.toByte()); result.addAll(nicknameData.toList())
    result.add(TLVType.NOISE_PUBLIC_KEY.value.toByte()); result.add(noisePublicKey.size.toByte()); result.addAll(noisePublicKey.toList())
    result.add(TLVType.SIGNING_PUBLIC_KEY.value.toByte()); result.add(signingPublicKey.size.toByte()); result.addAll(signingPublicKey.toList())
    return result.toByteArray()
}

fun decodeIdentityAnnouncement(data: ByteArray): IdentityAnnouncement? {
    val dataCopy = data.copyOf()
    var offset = 0
    var nickname: String? = null
    var noisePublicKey: ByteArray? = null
    var signingPublicKey: ByteArray? = null
    while (offset + 2 <= dataCopy.size) {
        val type = TLVType.values().find { it.value == dataCopy[offset].toUByte() }; offset += 1
        val length = dataCopy[offset].toUByte().toInt(); offset += 1
        if (offset + length > dataCopy.size) return null
        val value = dataCopy.sliceArray(offset until offset + length); offset += length
        when (type) {
            TLVType.NICKNAME -> nickname = String(value, Charsets.UTF_8)
            TLVType.NOISE_PUBLIC_KEY -> noisePublicKey = value
            TLVType.SIGNING_PUBLIC_KEY -> signingPublicKey = value
            null -> continue
        }
    }
    return if (nickname != null && noisePublicKey != null && signingPublicKey != null) IdentityAnnouncement(nickname!!, noisePublicKey!!, signingPublicKey!!) else null
}

// Fragment helpers
const val FRAGMENT_HEADER_SIZE = 13
const val FRAGMENT_ID_SIZE = 8

fun decodeFragmentPayload(payloadData: ByteArray): FragmentPayload? {
    if (payloadData.size < FRAGMENT_HEADER_SIZE) return null
    return try {
        val fragmentId = payloadData.sliceArray(0..<FRAGMENT_ID_SIZE)
        val index = ((payloadData[8].toInt() and 0xFF) shl 8) or (payloadData[9].toInt() and 0xFF)
        val total = ((payloadData[10].toInt() and 0xFF) shl 8) or (payloadData[11].toInt() and 0xFF)
        val originalType = payloadData[12].toUByte()
        val data = if (payloadData.size > FRAGMENT_HEADER_SIZE) payloadData.sliceArray(FRAGMENT_HEADER_SIZE..<payloadData.size) else ByteArray(0)
        FragmentPayload(fragmentId = fragmentId, index = index, total = total, originalType = originalType, data = data)
    } catch (e: Exception) { null }
}

fun encodeFragmentPayload(fp: FragmentPayload): ByteArray {
    val payload = ByteArray(FRAGMENT_HEADER_SIZE + fp.data.size)
    System.arraycopy(fp.fragmentId, 0, payload, 0, FRAGMENT_ID_SIZE)
    payload[8] = ((fp.index shr 8) and 0xFF).toByte()
    payload[9] = (fp.index and 0xFF).toByte()
    payload[10] = ((fp.total shr 8) and 0xFF).toByte()
    payload[11] = (fp.total and 0xFF).toByte()
    payload[12] = fp.originalType.toByte()
    if (fp.data.isNotEmpty()) System.arraycopy(fp.data, 0, payload, FRAGMENT_HEADER_SIZE, fp.data.size)
    return payload
}

fun generateFragmentID(): ByteArray {
    val fragmentId = ByteArray(FRAGMENT_ID_SIZE)
    kotlin.random.Random.nextBytes(fragmentId)
    return fragmentId
}

fun isValid(fp: FragmentPayload): Boolean {
    return fp.fragmentId.size == FRAGMENT_ID_SIZE && fp.index >= 0 && fp.total > 0 && fp.index < fp.total && fp.data.isNotEmpty()
}

