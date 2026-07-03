package com.app.transport.protocol

/**
 * Convert a hex peer ID string to the fixed 8-byte routing ID used in
 * BitchatPacket senderID/recipientID fields.
 *
 * Lenient zero-fill policy, byte-identical to the four historical private
 * copies it replaces (BinaryProtocol, MessageHandler, GossipSyncManager,
 * MeshOutboundSender): pairs that fail hex parsing and any missing tail
 * are left as zero bytes; input beyond 16 hex chars is ignored.
 *
 * NOTE: iOS (PeerID.routingData / Data(hexString:)) is stricter — it rejects
 * odd-length or non-hex input outright. All production callers pass locally
 * generated or wire-derived 16-hex IDs, for which both policies produce the
 * same bytes. Unifying the malformed-input policy with iOS is an owner
 * decision (see docs/TRANSPORT_ROBUSTNESS_ARCH_REVIEW.md L2).
 */
internal fun peerIdToRoutingBytes(hexString: String): ByteArray {
    val result = ByteArray(8) { 0 }
    var tempID = hexString
    var index = 0

    while (tempID.length >= 2 && index < 8) {
        val hexByte = tempID.substring(0, 2)
        val byte = hexByte.toIntOrNull(16)?.toByte()
        if (byte != null) {
            result[index] = byte
        }
        tempID = tempID.substring(2)
        index++
    }

    return result
}
