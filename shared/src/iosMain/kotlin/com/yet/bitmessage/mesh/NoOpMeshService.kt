package com.yet.bitmessage.mesh

import com.app.transport.mesh.BleDebugHandle
import com.app.transport.mesh.MeshService
import com.app.transport.mesh.PeerInfo
import com.app.transport.model.BitchatFilePacket
import com.app.transport.verification.VerifyEventListener

/**
 * Mesh facade for iOS while the CoreBluetooth mesh is not implemented: no peers, every send is a
 * silent no-op. The data layer treats an empty mesh exactly like "no peers in range", so the app
 * runs Nostr-only. Replace with the native mesh adapter when the CoreBluetooth work lands.
 */
class NoOpMeshService : MeshService {

    override val myPeerID: String = "0000000000000000"

    override val bleDebug: BleDebugHandle = object : BleDebugHandle {
        override fun startServer() = Unit
        override fun stopServer() = Unit
        override fun startClient() = Unit
        override fun stopClient() = Unit
        override fun connectedDeviceEntries(): List<Triple<String, Boolean, Int?>> = emptyList()
        override fun localAdapterAddress(): String? = null
        override fun connectToAddress(address: String): Boolean = false
        override fun disconnectAddress(address: String) = Unit
        override fun addressPeerSnapshot(): Map<String, String> = emptyMap()
        override fun debugInfo(): String = "mesh not available on iOS yet"
    }

    override fun getPeerInfo(peerID: String): PeerInfo? = null

    override fun getPeerNicknames(): Map<String, String> = emptyMap()

    override fun hasEstablishedSession(peerID: String): Boolean = false

    override fun initiateNoiseHandshake(peerID: String) = Unit

    override fun sendAnnouncementToPeer(peerID: String) = Unit

    override fun sendMessage(content: String, mentions: List<String>, channel: String?) = Unit

    override fun sendPrivateMessage(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String?,
    ) = Unit

    override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) = Unit

    override fun sendBroadcastAnnounce() = Unit

    override fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) = Unit

    override fun sendFileBroadcast(file: BitchatFilePacket) = Unit

    override fun cancelFileTransfer(transferId: String): Boolean = false

    override fun getDebugStatus(): String = "mesh not available on iOS yet"

    override var verifyEventListener: VerifyEventListener? = null

    override fun getPeerFingerprint(peerID: String): String? = null

    override fun getStaticNoisePublicKey(): ByteArray? = null

    override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) = Unit

    override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) = Unit
}
