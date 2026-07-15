package com.app.transport.mesh

import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BleSelfBroadcastTrackerTest {

    private fun packet(timestamp: ULong, payload: String = "message") = BitchatPacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        senderID = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        recipientID = SpecialRecipients.BROADCAST,
        timestamp = timestamp,
        payload = payload.encodeToByteArray(),
        signature = null,
        ttl = 7u,
    )

    @Test
    fun recordedIdIsTakenExactlyOnceForMatchingSelfReplay() {
        val tracker = BleSelfBroadcastTracker()
        val sent = packet(timestamp = 100u)

        tracker.record(messageID = "LOCAL-UUID", packet = sent, sentAtMillis = 1_000)

        assertEquals("LOCAL-UUID", tracker.takeMessageID(sent))
        assertNull(tracker.takeMessageID(sent))
    }

    @Test
    fun wireIdentityMatchesNativeSenderTimestampAndTypeKey() {
        val tracker = BleSelfBroadcastTracker()
        tracker.record("LOCAL-UUID", packet(timestamp = 100u, payload = "original"), 1_000)

        assertEquals("LOCAL-UUID", tracker.takeMessageID(packet(timestamp = 100u, payload = "replayed")))
    }

    @Test
    fun pruneAndClearRemoveStaleMappings() {
        val tracker = BleSelfBroadcastTracker()
        tracker.record("OLD", packet(100u), sentAtMillis = 1_000)
        tracker.record("NEW", packet(101u), sentAtMillis = 2_000)

        tracker.prune(beforeMillis = 1_500)
        assertNull(tracker.takeMessageID(packet(100u)))
        assertEquals("NEW", tracker.takeMessageID(packet(101u)))

        tracker.record("LAST", packet(102u), sentAtMillis = 3_000)
        tracker.clear()
        assertNull(tracker.takeMessageID(packet(102u)))
    }

    @Test
    fun capacityEvictsOldestInsertionDeterministically() {
        val tracker = BleSelfBroadcastTracker(capacity = 2)
        tracker.record("FIRST", packet(100u), sentAtMillis = 1_000)
        tracker.record("SECOND", packet(101u), sentAtMillis = 2_000)
        tracker.record("THIRD", packet(102u), sentAtMillis = 3_000)

        assertNull(tracker.takeMessageID(packet(100u)))
        assertEquals("SECOND", tracker.takeMessageID(packet(101u)))
        assertEquals("THIRD", tracker.takeMessageID(packet(102u)))
    }
}
