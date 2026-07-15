package com.app.transport.mesh

import com.app.transport.MeshConstants
import com.app.transport.model.BitchatFilePacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.peerIdToRoutingBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P0.4 boundary: exact 1 MiB incompressible content through createFragments → handleFragment
 * → BitchatFilePacket.decode (framed packet > 1_048_576 bytes).
 */
class FragmentOneMibFileInteropTest {

    @Test
    fun exactOneMibFile_fragmentsAndReassembles() {
        val size = MeshConstants.FileTransferLimits.MAX_PAYLOAD_BYTES
        // Incompressible pattern so compress path (if any) does not shrink below threshold.
        val content = ByteArray(size) { (it * 17 + 31).toByte() }
        val file = BitchatFilePacket(
            fileName = "big.bin",
            fileSize = size.toLong(),
            mimeType = "application/octet-stream",
            content = content,
        )
        val tlv = assertNotNull(file.encode())
        val outer = BitchatPacket(
            version = 2u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = peerIdToRoutingBytes("1122334455667788"),
            recipientID = peerIdToRoutingBytes("8877665544332211"),
            timestamp = 1_700_000_000_000uL,
            payload = tlv,
            signature = ByteArray(64) { 0x11 },
            ttl = 7u,
        )
        val encodedOuter = assertNotNull(outer.toBinaryData(padding = false))
        assertTrue(
            encodedOuter.size > MeshConstants.FileTransferLimits.MAX_PAYLOAD_BYTES,
            "framed packet must exceed bare 1 MiB content (was ${encodedOuter.size})",
        )
        assertTrue(
            encodedOuter.size <= MeshConstants.Fragmentation.MAX_FRAGMENT_TOTAL_BYTES,
            "framed packet ${encodedOuter.size} must fit fragment total cap " +
                "${MeshConstants.Fragmentation.MAX_FRAGMENT_TOTAL_BYTES}",
        )

        // Round-trip the outer frame without fragmentation first.
        val roundTrip = BitchatPacket.fromBinaryData(encodedOuter)
        assertNotNull(roundTrip, "outer packet must decode before fragmentation")

        val fm = FragmentManager()
        val fragments = fm.createFragments(outer)
        assertTrue(fragments.size > 1, "fragment count was ${fragments.size}")
        assertTrue(
            fragments.size <= MeshConstants.Fragmentation.MAX_FRAGMENTS_PER_ID,
            "too many fragments: ${fragments.size}",
        )

        var reassembled: BitchatPacket? = null
        var accepted = 0
        for (fragment in fragments) {
            val done = fm.handleFragment(fragment)
            if (done != null) {
                reassembled = done
            } else {
                // handleFragment returns null for intermediate pieces; count only via side effect.
                accepted++
            }
        }
        val full = assertNotNull(
            reassembled,
            "reassembly must complete for 1 MiB file " +
                "(fragments=${fragments.size}, intermediateNulls=$accepted, " +
                "encodedOuter=${encodedOuter.size})",
        )
        assertEquals(MessageType.FILE_TRANSFER.value, full.type)
        val decodedFile = assertNotNull(BitchatFilePacket.decode(full.payload))
        assertEquals(size, decodedFile.content.size)
        assertEquals(content.toList().take(16), decodedFile.content.toList().take(16))
        assertEquals(content.last(), decodedFile.content.last())
    }
}
