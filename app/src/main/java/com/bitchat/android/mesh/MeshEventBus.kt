package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatMessage
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Event bus for mesh service events.
 * Implements BluetoothMeshDelegate and exposes reactive flows for MVI stores to subscribe to.
 * This replaces the need for ChatViewModel as the delegate.
 */
@Singleton
class MeshEventBus @Inject constructor() : BluetoothMeshDelegate {

    // Connected peers state
    private val _connectedPeers = MutableStateFlow<List<String>>(emptyList())
    val connectedPeers: StateFlow<List<String>> = _connectedPeers.asStateFlow()

    // Message events (SharedFlow for events that shouldn't be replayed)
    private val _messageReceived = MutableSharedFlow<BitchatMessage>(extraBufferCapacity = 64)
    val messageReceived: SharedFlow<BitchatMessage> = _messageReceived.asSharedFlow()

    // Channel leave events
    private val _channelLeave = MutableSharedFlow<ChannelLeaveEvent>(extraBufferCapacity = 16)
    val channelLeave: SharedFlow<ChannelLeaveEvent> = _channelLeave.asSharedFlow()

    // Delivery acknowledgment events
    private val _deliveryAck = MutableSharedFlow<DeliveryAckEvent>(extraBufferCapacity = 32)
    val deliveryAck: SharedFlow<DeliveryAckEvent> = _deliveryAck.asSharedFlow()

    // Read receipt events
    private val _readReceipt = MutableSharedFlow<ReadReceiptEvent>(extraBufferCapacity = 32)
    val readReceipt: SharedFlow<ReadReceiptEvent> = _readReceipt.asSharedFlow()

    // Callbacks that need to be provided by the store/component
    var nicknameProvider: (() -> String?)? = null
    var favoriteChecker: ((String) -> Boolean)? = null
    var channelDecryptor: ((ByteArray, String) -> String?)? = null

    // BluetoothMeshDelegate implementation
    override fun didReceiveMessage(message: BitchatMessage) {
        _messageReceived.tryEmit(message)
    }

    override fun didUpdatePeerList(peers: List<String>) {
        _connectedPeers.value = peers
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        _channelLeave.tryEmit(ChannelLeaveEvent(channel, fromPeer))
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        _deliveryAck.tryEmit(DeliveryAckEvent(messageID, recipientPeerID))
    }

    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        _readReceipt.tryEmit(ReadReceiptEvent(messageID, recipientPeerID))
    }

    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return channelDecryptor?.invoke(encryptedContent, channel)
    }

    override fun getNickname(): String? {
        return nicknameProvider?.invoke()
    }

    override fun isFavorite(peerID: String): Boolean {
        return favoriteChecker?.invoke(peerID) ?: false
    }

    // Event data classes
    data class ChannelLeaveEvent(val channel: String, val fromPeer: String)
    data class DeliveryAckEvent(val messageID: String, val recipientPeerID: String)
    data class ReadReceiptEvent(val messageID: String, val recipientPeerID: String)
}
