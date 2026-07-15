package com.app.transport.mesh

import com.app.transport.model.BitchatFilePacket
import com.app.transport.protocol.BinaryProtocol
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.peerIdToRoutingBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * P0.3: private file uses outer FILE_TRANSFER 0x22 with directed recipient and
 * canonical BitchatFilePacket TLV (native BLEService.sendFilePrivate shape).
 */
class PrivateFileOuterPacketTest {

    private val sender = "0a0b0c0d0e0f1011"
    private val recipient = "1111111111111111"

    @Test
    fun outerFileTransfer_directedRoundTrip() {
        val file = BitchatFilePacket(
            fileName = "note.txt",
            fileSize = 5L,
            mimeType = "text/plain",
            content = "hello".encodeToByteArray(),
        )
        val tlv = assertNotNull(file.encode())
        val packet = BitchatPacket(
            version = 2u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = peerIdToRoutingBytes(sender),
            recipientID = peerIdToRoutingBytes(recipient),
            timestamp = 1_700_000_000_000uL,
            payload = tlv,
            signature = ByteArray(64) { 0x42 },
            ttl = 7u,
        )

        assertEquals(MessageType.FILE_TRANSFER.value, packet.type)
        assertEquals(0x22u.toUByte(), packet.type)
        assertEquals(2u.toUByte(), packet.version)
        assertNotNull(packet.recipientID)
        assertContentEquals(peerIdToRoutingBytes(recipient), packet.recipientID)

        val encoded = assertNotNull(BinaryProtocol.encode(packet, padding = false))
        val decoded = assertNotNull(BinaryProtocol.decode(encoded))
        assertEquals(MessageType.FILE_TRANSFER.value, decoded.type)
        assertEquals(2u.toUByte(), decoded.version)
        assertContentEquals(peerIdToRoutingBytes(recipient), decoded.recipientID)
        val decodedFile = assertNotNull(BitchatFilePacket.decode(decoded.payload))
        assertEquals("note.txt", decodedFile.fileName)
        assertEquals("hello", decodedFile.content.decodeToString())
        assertNotNull(decoded.signature)
    }

    @Test
    fun outerPrivateFile_isNotNoiseEncryptedType() {
        // Contract: native-compatible private file is plain directed 0x22, not Noise 0x11.
        assertEquals(0x22u.toUByte(), MessageType.FILE_TRANSFER.value)
        assertEquals(0x11u.toUByte(), MessageType.NOISE_ENCRYPTED.value)
        assertFalse(MessageType.FILE_TRANSFER.value == MessageType.NOISE_ENCRYPTED.value)
    }
}
