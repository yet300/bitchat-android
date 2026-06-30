package com.app.transport

import com.app.transport.model.BitchatMessage

/**
 * Sink for messages and peer lists received by the mesh engine.
 *
 * The mesh layer only writes here; the implementation is the process-wide in-memory store the UI
 * hydrates from, which lives in the app module (Branch by Abstraction — inverts the former direct
 * `mesh -> AppStateStore` call so mesh no longer depends on the app layer).
 */
interface IncomingMessageSink {
    fun setPeers(peerIds: List<String>)
    fun addPublicMessage(message: BitchatMessage)
    fun addPrivateMessage(peerId: String, message: BitchatMessage)
    fun addChannelMessage(channel: String, message: BitchatMessage)

    /** Advance the delivery ladder of [messageId] to Delivered (sent by [peerId]). */
    fun onDeliveryAck(messageId: String, peerId: String)

    /** Advance the delivery ladder of [messageId] to Read (read by [peerId]). */
    fun onReadReceipt(messageId: String, peerId: String)
}
