package com.app.transport.mesh

import com.app.transport.model.BitchatFilePacket

/**
 * commonMain contract for the mesh operations the data layer drives (peer directory, Noise session
 * control, message/file sending, debug surface). Implemented by the androidMain [BluetoothMeshService];
 * lets the routing / repository implementations in :core:data stay platform-free (DIP) without a
 * native mesh yet — the iOS actual is the deferred transport-native follow-up.
 *
 * Narrow lifecycle ([MeshLifecycleController]) and BLE-debug ([BleDebugHandle]) surfaces stay separate
 * (their own consumers); this is the broad app/data-facing facade.
 */
interface MeshService {

    /** Our current mesh ephemeral peer id (16-hex); rotates on panic reset. */
    val myPeerID: String

    /** BLE-specific debug surface for the debug settings sheet. */
    val bleDebug: BleDebugHandle

    fun getPeerInfo(peerID: String): PeerInfo?

    fun getPeerNicknames(): Map<String, String>

    fun hasEstablishedSession(peerID: String): Boolean

    fun initiateNoiseHandshake(peerID: String)

    fun sendAnnouncementToPeer(peerID: String)

    fun sendMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null)

    fun sendPrivateMessage(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String? = null,
    )

    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String)

    fun sendBroadcastAnnounce()

    fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket)

    fun sendFileBroadcast(file: BitchatFilePacket)

    fun cancelFileTransfer(transferId: String): Boolean

    fun getDebugStatus(): String
}
