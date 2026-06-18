package com.app.data.media

import android.util.Log
import com.app.domain.model.Attachment
import com.app.domain.model.ConversationId
import com.app.transport.features.file.FileUtils
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.model.BitchatFilePacket
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Re-homes the media orchestration deleted with the legacy `MediaSendingManager` (Phase D): turns a
 * domain [Attachment] (a local file reference) into a [BitchatFilePacket] and routes it over the
 * mesh — encrypted/private to a peer, broadcast for public/channel. The local echo + progress seed
 * is owned by `SendAttachmentUseCase`; this only performs the transport hand-off.
 */
@SingleIn(AppScope::class)
@Inject
class AttachmentSender(
    private val mesh: BluetoothMeshService,
    private val progressBridge: TransferProgressBridge,
) {

    suspend fun send(attachment: Attachment, target: ConversationId, messageId: String): Unit = withContext(Dispatchers.IO) {
        val file = File(attachment.ref)
        if (!file.exists()) {
            Log.e(TAG, "Attachment file does not exist: ${attachment.ref}")
            return@withContext
        }
        val mime = attachment.mime
            ?: runCatching { FileUtils.getMimeTypeFromExtension(file.name) }.getOrNull()
            ?: OCTET_STREAM
        val packet = BitchatFilePacket(
            fileName = file.name,
            fileSize = file.length(),
            mimeType = mime,
            content = file.readBytes(),
        )
        // Link the mesh transferId (sha256 of the encoded payload, derived identically inside BMS)
        // to this message so TransferProgressBridge can advance its delivery status.
        packet.encode()?.let { progressBridge.track(sha256Hex(it), messageId) }
        when (target) {
            is ConversationId.Private -> mesh.sendFilePrivate(target.peer.raw, packet)
            ConversationId.PublicMesh, is ConversationId.Channel -> mesh.sendFileBroadcast(packet)
            is ConversationId.Geohash ->
                // Geohash media would need a Nostr file transfer, which the legacy mesh path never
                // implemented; drop rather than mis-broadcast it onto the mesh.
                Log.w(TAG, "Geohash attachments are not supported yet; dropping ${file.name}")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "AttachmentSender"
        const val OCTET_STREAM = "application/octet-stream"
    }
}
