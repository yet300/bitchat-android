package com.app.data.media

import com.app.common.AppDispatchers
import com.app.common.encoding.hexEncodedString
import com.app.common.utils.Log
import com.app.crypto.hash.Sha256
import com.app.domain.model.Attachment
import com.app.domain.model.ConversationId
import com.app.transport.features.file.mimeTypeFromExtension
import com.app.transport.mesh.MeshService
import com.app.transport.model.BitchatFilePacket
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Re-homes the media orchestration deleted with the legacy `MediaSendingManager` (Phase D): turns a
 * domain [Attachment] (a local file reference) into a [BitchatFilePacket] and routes it over the
 * mesh — encrypted/private to a peer, broadcast for public/channel. The local echo + progress seed
 * is owned by `SendAttachmentUseCase`; this only performs the transport hand-off.
 */
@SingleIn(AppScope::class)
@Inject
class AttachmentSender(
    private val mesh: MeshService,
    private val progressBridge: TransferProgressBridge,
    private val dispatchers: AppDispatchers,
) {

    suspend fun send(attachment: Attachment, target: ConversationId, messageId: String): Unit = withContext(dispatchers.io) {
        val path = Path(attachment.ref)
        if (!SystemFileSystem.exists(path)) {
            Log.e(TAG, "Attachment file does not exist: ${attachment.ref}")
            return@withContext
        }
        val fileName = path.name
        val content = SystemFileSystem.source(path).buffered().use { it.readByteArray() }
        val mime = attachment.mime ?: mimeTypeFromExtension(fileName)
        val packet = BitchatFilePacket(
            fileName = fileName,
            fileSize = content.size.toLong(),
            mimeType = mime,
            content = content,
        )
        // Link the mesh transferId (sha256 of the encoded payload, derived identically inside BMS)
        // to this message so TransferProgressBridge can advance its delivery status.
        packet.encode()?.let { progressBridge.track(sha256Hex(it), messageId) }
        when (target) {
            is ConversationId.Private -> mesh.sendFilePrivate(target.peer.raw, packet)
            ConversationId.PublicMesh, is ConversationId.Channel -> mesh.sendFileBroadcast(packet)
            is ConversationId.Geohash ->
                // Parity boundary, not a gap: original bitchat transfers media over the BLE mesh
                // Noise session only. Files are never routed over Nostr — geohash channels are public
                // kind-20000 text events, and gift-wrapped DMs would exceed relay event-size limits.
                // Drop rather than mis-broadcast onto the mesh.
                Log.w(TAG, "Geohash/Nostr file transfer is unsupported by design (mesh-only); dropping $fileName")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = Sha256.digest(bytes).hexEncodedString()

    private companion object {
        const val TAG = "AttachmentSender"
    }
}
