package com.app.transport.protocol

import org.junit.Test

/** Temporary capture helper — prints current encode() output for the golden fixtures. */
class GoldenCapture {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun capture() {
        val sender = ByteArray(8) { (it + 1).toByte() }            // 0102030405060708
        val recipient = ByteArray(8) { (0x11 * (it + 1)).toByte() } // 112233...
        val signature = ByteArray(64) { it.toByte() }
        val timestamp = 1_700_000_000_000uL

        val v1Plain = BitchatPacket(
            version = 1u, type = MessageType.MESSAGE.value, senderID = sender,
            recipientID = null, timestamp = timestamp,
            payload = "hello bitchat".toByteArray(Charsets.UTF_8), ttl = 7u,
        )
        println("V1_PLAIN=" + BinaryProtocol.encode(v1Plain)!!.hex())

        val v1RecipSig = BitchatPacket(
            version = 1u, type = MessageType.MESSAGE.value, senderID = sender,
            recipientID = recipient, timestamp = timestamp,
            payload = "private hello".toByteArray(Charsets.UTF_8),
            signature = signature, ttl = 3u,
        )
        println("V1_RECIP_SIG=" + BinaryProtocol.encode(v1RecipSig)!!.hex())

        val v2Route = BitchatPacket(
            version = 2u, type = MessageType.MESSAGE.value, senderID = sender,
            recipientID = recipient, timestamp = timestamp,
            payload = "routed hello".toByteArray(Charsets.UTF_8), ttl = 5u,
            route = listOf(ByteArray(8) { 0x0A }, ByteArray(8) { 0x0B }),
        )
        println("V2_ROUTE=" + BinaryProtocol.encode(v2Route)!!.hex())

        val compressible = ByteArray(400) { 'A'.code.toByte() }
        val v1Compressed = BitchatPacket(
            version = 1u, type = MessageType.MESSAGE.value, senderID = sender,
            recipientID = null, timestamp = timestamp,
            payload = compressible, ttl = 7u,
        )
        println("V1_COMPRESSED=" + BinaryProtocol.encode(v1Compressed)!!.hex())

        val v2Compressed = BitchatPacket(
            version = 2u, type = MessageType.MESSAGE.value, senderID = sender,
            recipientID = recipient, timestamp = timestamp,
            payload = compressible, ttl = 7u,
        )
        println("V2_COMPRESSED=" + BinaryProtocol.encode(v2Compressed)!!.hex())
    }
}
