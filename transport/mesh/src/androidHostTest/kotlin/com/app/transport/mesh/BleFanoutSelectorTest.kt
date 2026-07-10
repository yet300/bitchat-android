package com.app.transport.mesh

import com.app.transport.protocol.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the iOS BLEFanoutSelector port (SYNC_SCALE P4): subset sizes, exemption of
 * fragment/announce/requestSync, and per-message determinism of the SHA256 pick.
 */
class BleFanoutSelectorTest {

    @Test
    fun subsetSizesMatchIos() {
        // min(count, floor(log2(count-1)) + 2) for count > 2.
        assertEquals(0, BleFanoutSelector.subsetSize(0))
        assertEquals(1, BleFanoutSelector.subsetSize(1))
        assertEquals(2, BleFanoutSelector.subsetSize(2))
        assertEquals(3, BleFanoutSelector.subsetSize(3))
        assertEquals(3, BleFanoutSelector.subsetSize(4))
        assertEquals(4, BleFanoutSelector.subsetSize(8))
        assertEquals(5, BleFanoutSelector.subsetSize(16))
        assertEquals(8, BleFanoutSelector.subsetSize(100))
    }

    @Test
    fun exemptTypesBypassTheSubset() {
        assertFalse(BleFanoutSelector.shouldSubset(MessageType.FRAGMENT.value))
        assertFalse(BleFanoutSelector.shouldSubset(MessageType.ANNOUNCE.value))
        assertFalse(BleFanoutSelector.shouldSubset(MessageType.REQUEST_SYNC.value))
        assertTrue(BleFanoutSelector.shouldSubset(MessageType.MESSAGE.value))
        assertTrue(BleFanoutSelector.shouldSubset(MessageType.LEAVE.value))
        assertTrue(BleFanoutSelector.shouldSubset(MessageType.FILE_TRANSFER.value))
    }

    @Test
    fun selectionIsDeterministicPerSeedAndSpreadsAcrossSeeds() {
        val links = (0 until 16).map { "link-$it" }
        val k = BleFanoutSelector.subsetSize(links.size) // 5

        val a1 = BleFanoutSelector.selectSubset(links, k, "message-A")
        val a2 = BleFanoutSelector.selectSubset(links, k, "message-A")
        assertEquals(a1, a2) // same message -> same links, every time
        assertEquals(k, a1.size)
        assertTrue(links.containsAll(a1))

        // Different messages pick different subsets often enough to spread load:
        // across 32 seeds every link must be chosen at least once.
        val seen = mutableSetOf<String>()
        repeat(32) { seen += BleFanoutSelector.selectSubset(links, k, "message-$it") }
        assertEquals(links.toSet(), seen)
    }

    @Test
    fun smallLinkSetsAreNeverSubset() {
        val two = listOf("a", "b")
        assertEquals(two.toSet(), BleFanoutSelector.selectSubset(two, BleFanoutSelector.subsetSize(2), "m"))
        val three = listOf("a", "b", "c")
        assertEquals(three.toSet(), BleFanoutSelector.selectSubset(three, BleFanoutSelector.subsetSize(3), "m"))
    }
}
