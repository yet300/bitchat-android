package com.app.data.media

import com.app.domain.model.Attachment
import com.app.domain.model.AttachmentKind
import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.PeerId
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.model.BitchatFilePacket
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.io.File

class AttachmentSenderTest {

    private val bytes = byteArrayOf(1, 2, 3, 4, 5)
    private lateinit var file: File
    private lateinit var mesh: BluetoothMeshService
    private lateinit var progressBridge: TransferProgressBridge
    private lateinit var sender: AttachmentSender

    @Before
    fun setUp() {
        file = File.createTempFile("att", ".jpg").apply { writeBytes(bytes); deleteOnExit() }
        mesh = mock()
        progressBridge = mock()
        sender = AttachmentSender(mesh, progressBridge)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    private fun attachment() = Attachment(
        kind = AttachmentKind.IMAGE,
        ref = file.absolutePath,
        mime = "image/jpeg",
        sizeBytes = bytes.size.toLong(),
    )

    @Test
    fun private_target_sends_an_encrypted_file_to_the_peer() = runTest {
        sender.send(attachment(), ConversationId.Private(PeerId("1234567890abcdef")), "MSG-1")

        val packet = argumentCaptor<BitchatFilePacket>()
        verify(mesh).sendFilePrivate(eq("1234567890abcdef"), packet.capture())
        verify(mesh, never()).sendFileBroadcast(any())
        verify(progressBridge).track(any(), eq("MSG-1"))
        assertEquals(file.name, packet.firstValue.fileName)
        assertEquals("image/jpeg", packet.firstValue.mimeType)
        assertArrayEquals(bytes, packet.firstValue.content)
    }

    @Test
    fun public_mesh_target_broadcasts_the_file() = runTest {
        sender.send(attachment(), ConversationId.PublicMesh, "MSG-1")

        verify(mesh).sendFileBroadcast(any())
        verify(mesh, never()).sendFilePrivate(any(), any())
    }

    @Test
    fun channel_target_broadcasts_the_file() = runTest {
        sender.send(attachment(), ConversationId.Channel("dev"), "MSG-1")

        verify(mesh).sendFileBroadcast(any())
        verify(mesh, never()).sendFilePrivate(any(), any())
    }

    @Test
    fun geohash_target_is_dropped_not_misrouted_to_the_mesh() = runTest {
        sender.send(attachment(), ConversationId.Geohash(GeohashChannel(GeohashLevel.CITY, "u4pru")), "MSG-1")

        verify(mesh, never()).sendFileBroadcast(any())
        verify(mesh, never()).sendFilePrivate(any(), any())
    }

    @Test
    fun missing_file_sends_nothing() = runTest {
        val missing = Attachment(kind = AttachmentKind.FILE, ref = "/no/such/path.bin")
        sender.send(missing, ConversationId.PublicMesh, "MSG-1")

        verify(mesh, never()).sendFileBroadcast(any())
        verify(mesh, never()).sendFilePrivate(any(), any())
        verify(progressBridge, never()).track(any(), any())
    }
}
