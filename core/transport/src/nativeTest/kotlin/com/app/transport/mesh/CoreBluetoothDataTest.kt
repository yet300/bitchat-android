package com.app.transport.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class CoreBluetoothDataTest {

    @Test
    fun roundTripPreservesAllByteValues() {
        val original = ByteArray(256) { it.toByte() } // 0x00..0xFF
        val restored = original.toNSData().toByteArray()
        assertTrue(original.contentEquals(restored), "NSData round-trip must preserve every byte")
    }

    @Test
    fun emptyArrayRoundTrips() {
        assertEquals(0, ByteArray(0).toNSData().toByteArray().size)
    }
}
