package com.app.transport.model

import com.app.transport.protocol.BinaryProtocol
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Frozen REQUEST_SYNC wire vectors, derived field-by-field from the iOS reference client
 * (bitchat-ios-client: Models/RequestSyncPacket.swift + BitFoundation/BinaryProtocol.swift).
 *
 * iOS RequestSyncPacket.decode requires, in TLV (type, len16 big-endian, value) form:
 *   0x01 P (uint8, 1 <= P <= GCSFilter.maxP = 32)
 *   0x02 M (uint32 big-endian, M > 0)
 *   0x03 data (opaque GR bitstream, <= 1024 bytes)
 * and ignores unknown TLVs (0x04 SyncTypeFlags / 0x05 sinceTimestamp / 0x06 fragment filter
 * are optional on iOS and absent from our encoder).
 *
 * These bytes MUST NOT change: iOS peers parse them. Fix the code, not the fixtures.
 */
class RequestSyncPacketGoldenTest {

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `golden - payload TLV with filter data`() {
        // P=7 (our default 1% FPR), M=256, 2-byte GR bitstream
        val encoded = RequestSyncPacket(p = 7, m = 256, data = byteArrayOf(0xAB.toByte(), 0xCD.toByte())).encode()
        assertEquals(
            "0100" + "0107" +          // TLV 0x01, len 1, P=7
                "0200" + "0400000100" + // TLV 0x02, len 4, M=256 big-endian
                "0300" + "02abcd",      // TLV 0x03, len 2, data
            encoded.hex(),
        )
    }

    @Test
    fun `golden - empty filter payload (nothing stored yet)`() {
        val encoded = RequestSyncPacket(p = 7, m = 1, data = ByteArray(0)).encode()
        assertEquals("010001070200040000000103" + "0000", encoded.hex())
    }

    @Test
    fun `golden - full REQUEST_SYNC frame as sent over BLE (unpadded, signed, addressed)`() {
        val payload = RequestSyncPacket(p = 7, m = 256, data = byteArrayOf(0xAB.toByte(), 0xCD.toByte())).encode()
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.REQUEST_SYNC.value, // 0x21, same on iOS
            senderID = ByteArray(8) { (it + 1).toByte() },
            recipientID = ByteArray(8) { (0x11 * (it + 1)).toByte() },
            timestamp = 1_700_000_000_000uL,
            payload = payload,
            signature = ByteArray(64) { it.toByte() },
            ttl = 0u, // sync is neighbor-only, same as iOS ttl: 0
        )
        // BleSendCore sends REQUEST_SYNC unpadded (BLEPacketPaddingPolicy: only Noise frames pad).
        val frame = BinaryProtocol.encode(packet, padding = false)
        assertNotNull(frame)
        assertEquals(
            "01" + "21" + "00" +                    // version, type REQUEST_SYNC, ttl 0
                "0000018bcfe56800" +                 // timestamp big-endian
                "03" +                               // flags: hasRecipient | hasSignature
                "0010" +                             // payload length 16
                "0102030405060708" +                 // senderID
                "1122334455667788" +                 // recipientID
                "0100010702000400000100030002abcd" + // REQUEST_SYNC TLV payload
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f",
            frame!!.hex(),
        )
        // iOS BinaryProtocol.decodeCore minimum: v1HeaderSize(14) + senderIDSize(8) = 22.
        assertEquals(14 + 8 + 8 + 16 + 64, frame.size)
    }

    @Test
    fun `decode tolerates iOS optional TLVs 0x04 0x05 0x06`() {
        // As encoded by iOS RequestSyncPacket with types=.publicMessages (bits 0|1 = 0x03),
        // sinceTimestamp and a fragment-id filter string.
        val iosPayload =
            "0100" + "0107" +                 // P=7
                "0200" + "0400000100" +       // M=256
                "0300" + "02abcd" +           // data
                "0400" + "0103" +             // SyncTypeFlags announce|message
                "0500" + "080000018bcfe56800" + // sinceTimestamp
                "0600" + "0461626364"         // "abcd" fragment filter
        val decoded = RequestSyncPacket.decode(iosPayload.unhex())
        assertNotNull("iOS payload with optional TLVs must decode", decoded)
        assertEquals(7, decoded!!.p)
        assertEquals(256L, decoded.m)
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), decoded.data)
    }

    // ── SYNC_SCALE P3 wire extension (owner-approved, Q1): we now EMIT the optional
    // 0x04 types / 0x05 sinceTimestamp TLVs, appended after 0x03 in iOS encode order.
    // Constructors that omit them keep producing the frozen 3-TLV bytes above.

    @Test
    fun `golden P3 - optional types TLV 0x04 (publicMessages = announce or message = 0x03)`() {
        // iOS RequestSyncPacket(p:7, m:256, data:abcd, types:.publicMessages).encode():
        // SyncTypeFlags.toData() = LE u64 trailing-zero-trimmed -> single byte 0x03.
        val encoded = RequestSyncPacket(
            p = 7,
            m = 256,
            data = byteArrayOf(0xAB.toByte(), 0xCD.toByte()),
            types = SyncTypeFlags.publicMessages,
        ).encode()
        assertEquals(
            "0100" + "0107" +          // TLV 0x01, len 1, P=7
                "0200" + "0400000100" + // TLV 0x02, len 4, M=256 big-endian
                "0300" + "02abcd" +     // TLV 0x03, len 2, data
                "0400" + "0103",        // TLV 0x04, len 1, types=announce|message
            encoded.hex(),
        )
    }

    @Test
    fun `golden P3 - types plus sinceTimestamp TLV 0x05 (u64 BE)`() {
        // sinceTimestamp = 1_700_000_000_000 = 0x0000018bcfe56800, big-endian u64.
        val encoded = RequestSyncPacket(
            p = 7,
            m = 256,
            data = byteArrayOf(0xAB.toByte(), 0xCD.toByte()),
            types = SyncTypeFlags.publicMessages,
            sinceTimestamp = 1_700_000_000_000L,
        ).encode()
        assertEquals(
            "0100" + "0107" +
                "0200" + "0400000100" +
                "0300" + "02abcd" +
                "0400" + "0103" +
                "0500" + "080000018bcfe56800", // TLV 0x05, len 8, since BE
            encoded.hex(),
        )
    }

    @Test
    fun `golden P5 - typed board prekey and group requests use two-byte little-endian flags`() {
        fun request(types: SyncTypeFlags) = RequestSyncPacket(
            p = 7,
            m = 1,
            data = ByteArray(0),
            types = types,
        ).encode().hex()

        val prefix = "0100" + "0107" + "0200" + "0400000001" + "0300" + "00" + "0400" + "02"
        assertEquals(prefix + "0001", request(SyncTypeFlags.boardPost))
        assertEquals(prefix + "0002", request(SyncTypeFlags.prekeyBundle))
        assertEquals(prefix + "0004", request(SyncTypeFlags.groupMessage))
    }

    @Test
    fun `golden P3 - omitted optionals stay byte-identical to the frozen 3-TLV form`() {
        val legacy = RequestSyncPacket(p = 7, m = 256, data = byteArrayOf(0xAB.toByte(), 0xCD.toByte()))
        assertEquals("0100" + "0107" + "0200" + "0400000100" + "0300" + "02abcd", legacy.encode().hex())
    }

    @Test
    fun `decode P3 - populates types and sinceTimestamp from the iOS 6-TLV payload`() {
        val iosPayload =
            "0100" + "0107" +
                "0200" + "0400000100" +
                "0300" + "02abcd" +
                "0400" + "0103" +               // types = announce|message
                "0500" + "080000018bcfe56800" + // since = 1_700_000_000_000
                "0600" + "0461626364"           // fragment filter: still skipped
        val decoded = RequestSyncPacket.decode(iosPayload.unhex())
        assertNotNull(decoded)
        assertEquals(SyncTypeFlags.publicMessages, decoded!!.types)
        assertEquals(1_700_000_000_000L, decoded.sinceTimestamp)
    }

    @Test
    fun `decode P3 - multi-byte LE types field with unknown high bits masked`() {
        // 2-byte LE flags 0xFF 0xFF = raw 0xFFFF; only bits 0..10 map to types -> 0x7FF.
        val payload =
            "0100" + "0107" +
                "0200" + "0400000100" +
                "0300" + "02abcd" +
                "0400" + "02ffff"
        val decoded = RequestSyncPacket.decode(payload.unhex())
        assertNotNull(decoded)
        assertEquals(SyncTypeFlags.fromRaw(0x7FFuL), decoded!!.types)
        // And the trimmed re-encoding of the known mask is 2 bytes LE: ff 07.
        assertEquals("ff07", SyncTypeFlags.fromRaw(0x7FFuL).toData()!!.hex())
    }

    @Test
    fun `round trip through frame decode`() {
        val payload = RequestSyncPacket(p = 7, m = 1, data = ByteArray(0)).encode()
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.REQUEST_SYNC.value,
            senderID = ByteArray(8) { (it + 1).toByte() },
            recipientID = null,
            timestamp = 1_700_000_000_000uL,
            payload = payload,
            signature = null,
            ttl = 0u,
        )
        val frame = BinaryProtocol.encode(packet, padding = false)!!
        val decoded = BinaryProtocol.decode(frame)
        assertNotNull(decoded)
        val sync = RequestSyncPacket.decode(decoded!!.payload)
        assertNotNull(sync)
        assertEquals(7, sync!!.p)
        assertEquals(1L, sync.m)
        assertEquals(0, sync.data.size)
    }
}
