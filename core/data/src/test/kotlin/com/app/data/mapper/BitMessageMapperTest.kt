@file:OptIn(ExperimentalTime::class)

package com.app.data.mapper

import com.app.domain.model.AttachmentKind
import com.app.domain.model.ConversationId
import com.app.domain.model.MessageType
import com.app.domain.model.TransferState
import com.app.transport.model.BitchatMessage
import com.app.transport.model.BitchatMessageType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class BitMessageMapperTest {

    private val conversation = ConversationId.PublicMesh
    private val temps = mutableListOf<File>()

    @After
    fun tearDown() {
        temps.forEach { it.delete() }
    }

    private fun tempFile(suffix: String, bytes: ByteArray): File =
        File.createTempFile("att", suffix).apply { writeBytes(bytes); temps += this }

    private fun wire(type: BitchatMessageType, content: String) = BitchatMessage(
        id = "MSG-1",
        sender = "alice",
        content = content,
        type = type,
        timestamp = Instant.fromEpochMilliseconds(0),
        senderPeerID = "abc",
    )

    @Test
    fun text_message_maps_to_null_attachment() {
        val msg = wire(BitchatMessageType.Message, "hello").toDomain(conversation, myPeerId = "me")
        assertEquals(MessageType.TEXT, msg.type)
        assertNull(msg.attachment)
    }

    @Test
    fun image_message_maps_attachment_from_file_on_disk() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val file = tempFile(".jpg", bytes)
        val msg = wire(BitchatMessageType.Image, file.absolutePath).toDomain(conversation, myPeerId = "me")

        val att = requireNotNull(msg.attachment)
        assertEquals(AttachmentKind.IMAGE, att.kind)
        assertEquals(file.absolutePath, att.ref)
        assertEquals("image/jpeg", att.mime)
        assertEquals(bytes.size.toLong(), att.sizeBytes)
        assertEquals(TransferState.Done, att.transfer)
    }

    @Test
    fun audio_message_maps_attachment_with_audio_mime() {
        val bytes = byteArrayOf(9, 8, 7)
        val file = tempFile(".m4a", bytes)
        val att = requireNotNull(wire(BitchatMessageType.Audio, file.absolutePath).toDomain(conversation, "me").attachment)
        assertEquals(AttachmentKind.AUDIO, att.kind)
        assertEquals("audio/mp4", att.mime)
        assertEquals(bytes.size.toLong(), att.sizeBytes)
    }

    @Test
    fun file_message_maps_attachment_kind_file() {
        val file = tempFile(".bin", byteArrayOf(0))
        val att = requireNotNull(wire(BitchatMessageType.File, file.absolutePath).toDomain(conversation, "me").attachment)
        assertEquals(AttachmentKind.FILE, att.kind)
        assertEquals(1L, att.sizeBytes)
    }

    @Test
    fun media_with_missing_file_has_null_size_but_still_maps() {
        val att = requireNotNull(wire(BitchatMessageType.Image, "/no/such/path.png").toDomain(conversation, "me").attachment)
        assertEquals(AttachmentKind.IMAGE, att.kind)
        assertEquals("image/png", att.mime)
        assertNull(att.sizeBytes)
        assertEquals(TransferState.Done, att.transfer)
    }

    @Test
    fun blank_content_media_maps_to_null_attachment() {
        val msg = wire(BitchatMessageType.Image, "   ").toDomain(conversation, "me")
        assertEquals(MessageType.IMAGE, msg.type)
        assertNull(msg.attachment)
    }
}
