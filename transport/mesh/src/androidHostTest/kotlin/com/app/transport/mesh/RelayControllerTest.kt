package com.app.transport.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the iOS RelayController.decide() port (SYNC_SCALE P4): degree-based broadcast
 * TTL clamp, always-relay for session-critical/directed traffic, REQUEST_SYNC never
 * relayed, ttl<=1 suppression. Degree is the LOCAL link count.
 */
class RelayControllerTest {

    private fun decide(
        ttl: UByte,
        degree: Int,
        isAnnounce: Boolean = false,
        isFragment: Boolean = false,
        isVoiceFrame: Boolean = false,
        isDirectedFragment: Boolean = false,
        isHandshake: Boolean = false,
        isDirectedEncrypted: Boolean = false,
        isRequestSync: Boolean = false,
        senderIsSelf: Boolean = false,
        recipientIsSelf: Boolean = false,
    ) = RelayController.decide(
        ttl = ttl,
        senderIsSelf = senderIsSelf,
        recipientIsSelf = recipientIsSelf,
        isDirectedEncrypted = isDirectedEncrypted,
        isFragment = isFragment,
        isVoiceFrame = isVoiceFrame,
        isDirectedFragment = isDirectedFragment,
        isHandshake = isHandshake,
        isAnnounce = isAnnounce,
        isRequestSync = isRequestSync,
        degree = degree,
    )

    @Test
    fun denseGraphClampsBroadcastTtlToFive() {
        // degree >= 6: clamp(ttl, 2..5), then -1 -> outgoing 4 for any incoming >= 5.
        val d = decide(ttl = 7u, degree = 6)
        assertTrue(d.shouldRelay)
        assertEquals(4, d.newTTL.toInt())
        assertEquals(4, decide(ttl = 5u, degree = 10).newTTL.toInt())
        // Incoming 3 in a dense graph: limit=3, outgoing 2.
        assertEquals(2, decide(ttl = 3u, degree = 8).newTTL.toInt())
    }

    @Test
    fun thinChainRelaysAtFullDepth() {
        // degree <= 2: every hop counts -> no clamp beyond the global 7 cap.
        val d = decide(ttl = 7u, degree = 2)
        assertTrue(d.shouldRelay)
        assertEquals(6, d.newTTL.toInt())
        assertEquals(6, decide(ttl = 7u, degree = 0).newTTL.toInt())
    }

    @Test
    fun midDegreeClampsToSixAndAnnounceGetsSeven() {
        // degree 3..5: clamp(ttl, 2..6) -> outgoing 5; announce headroom 7 -> outgoing 6.
        assertEquals(5, decide(ttl = 7u, degree = 4).newTTL.toInt())
        assertEquals(6, decide(ttl = 7u, degree = 4, isAnnounce = true).newTTL.toInt())
    }

    @Test
    fun ttlAboveSevenIsCappedFirst() {
        // ttlCap = min(ttl, 7): a hostile 255 behaves exactly like 7.
        assertEquals(6, decide(ttl = 255u, degree = 2).newTTL.toInt())
        assertEquals(4, decide(ttl = 255u, degree = 6).newTTL.toInt())
    }

    @Test
    fun requestSyncIsNeverRelayed() {
        assertFalse(decide(ttl = 7u, degree = 2, isRequestSync = true).shouldRelay)
    }

    @Test
    fun ttlOneOrSelfTrafficIsSuppressed() {
        assertFalse(decide(ttl = 1u, degree = 2).shouldRelay)
        assertFalse(decide(ttl = 0u, degree = 2).shouldRelay)
        assertFalse(decide(ttl = 7u, degree = 2, senderIsSelf = true).shouldRelay)
        assertFalse(decide(ttl = 7u, degree = 2, recipientIsSelf = true).shouldRelay)
    }

    @Test
    fun sessionCriticalTrafficAlwaysRelaysWithoutDegreeClamp() {
        // Handshake / directed fragment / directed encrypted: newTTL = ttlCap - 1 even dense.
        assertEquals(6, decide(ttl = 7u, degree = 10, isHandshake = true).newTTL.toInt())
        assertEquals(6, decide(ttl = 7u, degree = 10, isDirectedFragment = true, isFragment = true).newTTL.toInt())
        assertEquals(6, decide(ttl = 7u, degree = 10, isDirectedEncrypted = true).newTTL.toInt())
        assertTrue(decide(ttl = 7u, degree = 10, isHandshake = true).shouldRelay)
    }

    @Test
    fun broadcastFragmentsClampToFiveInDenseGraphs() {
        // Dense: cap 5 -> outgoing 4; sparse: cap 7 -> outgoing 6.
        assertEquals(4, decide(ttl = 7u, degree = 6, isFragment = true).newTTL.toInt())
        assertEquals(6, decide(ttl = 7u, degree = 2, isFragment = true).newTTL.toInt())
    }

    @Test
    fun publicVoiceFramesUseTheFragmentTtlClamp() {
        assertEquals(4, decide(ttl = 7u, degree = 6, isVoiceFrame = true).newTTL.toInt())
        assertEquals(6, decide(ttl = 7u, degree = 2, isVoiceFrame = true).newTTL.toInt())
    }
}
