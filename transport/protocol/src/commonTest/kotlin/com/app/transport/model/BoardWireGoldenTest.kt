package com.app.transport.model

import com.app.common.encoding.hexEncodedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen wire vectors for the geohash board (`MessageType.BOARD_POST = 0x23`), derived by hand from
 * the reference iOS `Protocols/BoardPackets.swift` — never captured from a run.
 *
 * Board signing bytes are a pure length-prefixed concatenation (no hashing), so the exact bytes are
 * pinned here. TLV is type u8 + **len u16 BE** (matching REQUEST_SYNC / courier / groups).
 */
class BoardWireGoldenTest {

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    private fun rep(byte: String, n: Int) = byte.repeat(n)

    private val postIDHex = "000102030405060708090a0b0c0d0e0f"
    private val authorKeyHex = rep("02", 32)
    private val signatureHex = rep("04", 64)
    private val geohash = "9q8yy"
    private val geohashHex = "3971387979"
    private val contentHex = "68656c6c6f" // "hello"
    private val nickHex = "626f62"       // "bob"
    private val createdAtHex = "00000000000003e8" // 1000
    private val expiresAtHex = "00000000000007d0" // 2000

    private fun post(flags: UByte = 1u) = BoardPostPacket(
        postID = bytes(postIDHex),
        geohash = geohash,
        content = "hello",
        authorSigningKey = bytes(authorKeyHex),
        authorNickname = "bob",
        createdAt = 1000u,
        expiresAt = 2000u,
        flags = flags,
        signature = bytes(signatureHex),
    )

    @Test
    fun `golden - post signing bytes are the frozen concatenation`() {
        val expected =
            "10" + "626974636861742d626f6172642d7631" + // len8("bitchat-board-v1")
                postIDHex +
                "0005" + geohashHex + // lp(geohash)
                "0005" + contentHex + // lp(content)
                authorKeyHex +
                "0003" + nickHex +    // lp(nickname)
                createdAtHex +
                expiresAtHex +
                "01"                  // flags
        assertEquals(expected, post().signingBytes.hexEncodedString())
    }

    @Test
    fun `golden - tombstone signing bytes are the frozen concatenation`() {
        val tombstone = BoardTombstonePacket(bytes(postIDHex), bytes(authorKeyHex), 3000u, bytes(signatureHex))
        val expected =
            "14" + "626974636861742d626f6172642d64656c2d7631" + // len8("bitchat-board-del-v1")
                postIDHex +
                "0000000000000bb8" // deletedAt = 3000
        assertEquals(expected, tombstone.signingBytes.hexEncodedString())
    }

    @Test
    fun `golden - post encodes to the frozen TLV bytes`() {
        val expected =
            "01" + "0001" + "01" +                 // kind = post
                "02" + "0010" + postIDHex +
                "03" + "0005" + geohashHex +
                "04" + "0005" + contentHex +
                "05" + "0020" + authorKeyHex +
                "06" + "0003" + nickHex +
                "07" + "0008" + createdAtHex +
                "08" + "0008" + expiresAtHex +
                "09" + "0001" + "01" +
                "0a" + "0040" + signatureHex
        assertEquals(expected, BoardWire.Post(post()).encode().hexEncodedString())
        assertEquals(BoardWire.Post(post()), BoardWire.decode(bytes(expected)))
    }

    @Test
    fun `golden - tombstone encodes to the frozen TLV bytes`() {
        val tombstone = BoardTombstonePacket(bytes(postIDHex), bytes(authorKeyHex), 3000u, bytes(signatureHex))
        val expected =
            "01" + "0001" + "02" +                 // kind = tombstone
                "02" + "0010" + postIDHex +
                "05" + "0020" + authorKeyHex +
                "0b" + "0008" + "0000000000000bb8" +
                "0a" + "0040" + signatureHex
        assertEquals(expected, BoardWire.Tombstone(tombstone).encode().hexEncodedString())
        assertEquals(BoardWire.Tombstone(tombstone), BoardWire.decode(bytes(expected)))
    }

    @Test
    fun `urgentFlag peek matches the flags byte without a full decode`() {
        assertTrue(BoardWire.urgentFlag(BoardWire.Post(post(flags = 1u)).encode()))
        assertFalse(BoardWire.urgentFlag(BoardWire.Post(post(flags = 0u)).encode()))
    }

    @Test
    fun `decode skips an unknown TLV`() {
        val encoded = BoardWire.Post(post()).encode()
        val withUnknown = encoded + bytes("7f0002cafe")
        assertEquals(BoardWire.Post(post()), BoardWire.decode(withUnknown))
    }

    @Test
    fun `decode rejects over-long content`() {
        // content TLV length 513 (> 512) with a matching value.
        val big = "04" + "0201" + rep("61", 513)
        val bad = "01" + "0001" + "01" + "02" + "0010" + postIDHex + "03" + "0005" + geohashHex + big +
            "05" + "0020" + authorKeyHex + "06" + "0003" + nickHex +
            "07" + "0008" + createdAtHex + "08" + "0008" + expiresAtHex + "09" + "0001" + "00" +
            "0a" + "0040" + signatureHex
        assertNull(BoardWire.decode(bytes(bad)))
    }

    @Test
    fun `decode rejects a lifetime over 7 days`() {
        val eightDays = 8L * 24 * 60 * 60 * 1000
        val p = BoardPostPacket(bytes(postIDHex), geohash, "hi", bytes(authorKeyHex), "bob", 0u, eightDays.toULong(), 0u, bytes(signatureHex))
        assertNull(BoardWire.decode(BoardWire.Post(p).encode()))
    }

    @Test
    fun `decode rejects an invalid geohash character`() {
        val p = BoardPostPacket(bytes(postIDHex), "abc", "hi", bytes(authorKeyHex), "bob", 0u, 1000u, 0u, bytes(signatureHex))
        // 'a' and 'i' are not in the geohash alphabet.
        assertNull(BoardWire.decode(BoardWire.Post(p).encode()))
    }

    @Test
    fun `verifySignature invokes the injected verifier with author key and signing bytes`() {
        var seenKey: ByteArray? = null
        var seenData: ByteArray? = null
        val ok = post().verifySignature { key, data, _ -> seenKey = key; seenData = data; true }
        assertTrue(ok)
        assertEquals(authorKeyHex, seenKey!!.hexEncodedString())
        assertEquals(post().signingBytes.hexEncodedString(), seenData!!.hexEncodedString())
    }
}
