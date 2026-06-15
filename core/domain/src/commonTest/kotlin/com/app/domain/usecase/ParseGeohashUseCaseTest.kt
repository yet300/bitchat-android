package com.app.domain.usecase

import com.app.domain.model.GeohashLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParseGeohashUseCaseTest {

    private val parse = ParseGeohashUseCase()

    @Test fun parses_a_bare_geohash_and_derives_the_level_from_length() {
        val channel = parse("u4pruyd")
        assertEquals("u4pruyd", channel?.geohash)
        assertEquals(GeohashLevel.BLOCK, channel?.level) // length 7
    }

    @Test fun strips_a_leading_hash_and_lowercases() {
        assertEquals("u4pr", parse("#U4PR")?.geohash)
    }

    @Test fun length_maps_to_the_finest_level_not_exceeding_it() {
        assertEquals(GeohashLevel.CITY, parse("u4pru")?.level)        // 5
        assertEquals(GeohashLevel.BUILDING, parse("u4pruydqq")?.level) // 9 -> 8
        assertEquals(GeohashLevel.REGION, parse("u4p")?.level)         // 3 -> 2
    }

    @Test fun rejects_invalid_characters_and_out_of_range_lengths() {
        assertNull(parse("u")) // too short
        assertNull(parse("ailo")) // letters outside base32
        assertNull(parse("   "))
        assertNull(parse("u4pruydqqxyz0")) // 13, too long
    }
}
