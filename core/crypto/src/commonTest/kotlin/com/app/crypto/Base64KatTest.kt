@file:OptIn(ExperimentalEncodingApi::class)

package com.app.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Pins the at-rest Base64 variant used by :core:crypto storage after migrating off
 * android.util.Base64: standard alphabet, padded, NO line wrapping. This is byte-identical
 * to the former android.util.Base64.NO_WRAP and stays self-consistent for the DEFAULT call
 * sites (clean-start storage, no on-wire use). Guards against an accidental variant change.
 */
class Base64KatTest {

    @Test
    fun fixed_vector_encodes_to_standard_padded_base64() {
        val bytes = byteArrayOf(0, 1, 2, 0xFD.toByte(), 0xFE.toByte(), 0xFF.toByte())
        assertEquals("AAEC/f7/", Base64.encode(bytes))
    }

    @Test
    fun round_trips_all_byte_values() {
        val data = ByteArray(256) { it.toByte() }
        assertContentEquals(data, Base64.decode(Base64.encode(data)))
    }

    @Test
    fun does_not_wrap_long_input() {
        val encoded = Base64.encode(ByteArray(120))
        assertEquals(-1, encoded.indexOf('\n'))
    }
}
