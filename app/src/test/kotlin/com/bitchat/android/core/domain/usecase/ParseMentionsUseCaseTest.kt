package com.bitchat.android.core.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

class ParseMentionsUseCaseTest {

    private val parse = ParseMentionsUseCase()

    @Test fun `keeps only known nicknames`() {
        val result = parse("hi @alice and @bob and @ghost", setOf("alice", "bob"))
        assertEquals(listOf("alice", "bob"), result)
    }

    @Test fun `deduplicates`() {
        val result = parse("@alice @alice @alice", setOf("alice"))
        assertEquals(listOf("alice"), result)
    }

    @Test fun `no mentions yields empty`() {
        assertEquals(emptyList<String>(), parse("nothing here", setOf("alice")))
    }
}
