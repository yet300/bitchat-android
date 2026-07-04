package com.app.transport.model

import com.app.transport.protocol.MessagePadding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Frozen wire-format vectors for the value/model serializers migrated to commonMain in the
 * :core:transport KMP split (Phase 1). Each fixture is the exact encode() output for a fully
 * specified input, captured from the pre-migration (java.nio.ByteBuffer) implementation.
 *
 * These bytes MUST NOT change: the de-JVM rewrite (ByteBuffer -> kotlinx-io, Charsets ->
 * encodeToByteArray) changes the LANGUAGE, not the BYTES. If any assertion fails, the codec
 * drifted and breaks iOS wire compatibility — fix the code, do not update the fixtures.
 */
@OptIn(ExperimentalTime::class)
class ModelWireGoldenTest {

    private fun ByteArray.hex() =
        joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private val ts = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @Test
    fun `golden - BitchatMessage all optional fields`() {
        val msg = BitchatMessage(
            id = "MSG-ID-1",
            sender = "alice",
            content = "hello world",
            type = BitchatMessageType.Message,
            timestamp = ts,
            isRelay = true,
            originalSender = "orig",
            isPrivate = true,
            recipientNickname = "bob",
            senderPeerID = "peer01",
            mentions = listOf("@x", "@yy"),
            channel = "#general",
            isEncrypted = false,
        )
        val encoded = msg.toBinaryPayload()
        assertNotNull(encoded)
        assertEquals(EXPECTED_BITCHAT_MESSAGE, encoded.hex())
        // round-trip
        assertEquals(msg.copy(deliveryStatus = null), BitchatMessage.fromBinaryPayload(encoded))
    }

    @Test
    fun `golden - BitchatMessage encrypted branch`() {
        val msg = BitchatMessage(
            id = "EID",
            sender = "s",
            content = "",
            timestamp = ts,
            encryptedContent = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            isEncrypted = true,
        )
        assertEquals(EXPECTED_BITCHAT_MESSAGE_ENC, msg.toBinaryPayload()!!.hex())
    }

    @Test
    fun `golden - BitchatFilePacket`() {
        val pkt = BitchatFilePacket(
            fileName = "a.txt",
            fileSize = 5,
            mimeType = "text/plain",
            content = byteArrayOf(1, 2, 3, 4, 5),
        )
        assertEquals(EXPECTED_FILE_PACKET, pkt.encode()!!.hex())
    }

    @Test
    fun `golden - FragmentPayload`() {
        val frag = FragmentPayload(
            fragmentID = ByteArray(8) { it.toByte() },
            index = 2,
            total = 7,
            originalType = 0x20u,
            data = byteArrayOf(9, 8, 7),
        )
        assertEquals(EXPECTED_FRAGMENT, frag.encode().hex())
    }

    @Test
    fun `golden - IdentityAnnouncement`() {
        val ann = IdentityAnnouncement(
            nickname = "nick",
            noisePublicKey = ByteArray(32) { it.toByte() },
            signingPublicKey = ByteArray(32) { (it + 100).toByte() },
        )
        assertEquals(EXPECTED_IDENTITY, ann.encode()!!.hex())
    }

    @Test
    fun `golden - NoisePayload`() {
        val p = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, byteArrayOf(1, 2, 3))
        assertEquals(EXPECTED_NOISE_PAYLOAD, p.encode().hex())
    }

    @Test
    fun `golden - PrivateMessagePacket`() {
        val p = PrivateMessagePacket(messageID = "m1", content = "hi")
        assertEquals(EXPECTED_PRIVATE_MESSAGE, p.encode()!!.hex())
    }

    @Test
    fun `golden - RequestSyncPacket`() {
        val p = RequestSyncPacket(p = 3, m = 64L, data = byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        assertEquals(EXPECTED_REQUEST_SYNC, p.encode().hex())
    }

    @Test
    fun `golden - MessagePadding pad`() {
        val padded = MessagePadding.pad(byteArrayOf(1, 2, 3), 10)
        assertEquals(EXPECTED_PADDING, padded.hex())
    }

    private companion object {
        const val EXPECTED_BITCHAT_MESSAGE =
            "7f0000018bcfe56800084d53472d49442d3105616c696365000b68656c6c6f20776f726c64" +
                "046f72696703626f62067065657230310202407803407979082367656e6572616c"
        const val EXPECTED_BITCHAT_MESSAGE_ENC =
            "800000018bcfe568000345494401730004deadbeef"
        const val EXPECTED_FILE_PACKET =
            "010005612e7478740200040000000503000a746578742f706c61696e04000000050102030405"
        const val EXPECTED_FRAGMENT = "00010203040506070002000720090807"
        const val EXPECTED_IDENTITY =
            "01046e69636b0220000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "03206465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f80818283"
        const val EXPECTED_NOISE_PAYLOAD = "01010203"
        const val EXPECTED_PRIVATE_MESSAGE = "00026d3101026869"
        const val EXPECTED_REQUEST_SYNC = "0100010302000400000040030002aabb"
        const val EXPECTED_PADDING = "01020307070707070707"
    }
}
