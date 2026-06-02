package com.app.domain.usecase

import com.app.domain.FakeGeohashRepository
import com.app.domain.model.ConversationId
import com.app.domain.model.PeerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StartGeohashDmUseCaseTest {

    private val pubkey = "abcdef0123456789abcdef0123456789"
    private val expected = ConversationId.Private(PeerId("nostr_${pubkey.take(16)}"))

    @Test fun `starts dm by pubkey`() = runTest {
        val useCase = StartGeohashDmUseCase(FakeGeohashRepository())
        assertEquals(expected, useCase(GeohashDmTarget.Pubkey(pubkey)))
    }

    @Test fun `resolves nickname then starts dm`() = runTest {
        val repo = FakeGeohashRepository(nicknameToPubkey = mapOf("alice" to pubkey))
        assertEquals(expected, StartGeohashDmUseCase(repo).invoke(GeohashDmTarget.Nickname("alice")))
    }

    @Test fun `resolves short id then starts dm`() = runTest {
        val repo = FakeGeohashRepository(shortIdToPubkey = mapOf("ab12" to pubkey))
        assertEquals(expected, StartGeohashDmUseCase(repo).invoke(GeohashDmTarget.ShortId("ab12")))
    }

    @Test fun `unknown nickname yields null`() = runTest {
        assertNull(StartGeohashDmUseCase(FakeGeohashRepository()).invoke(GeohashDmTarget.Nickname("ghost")))
    }
}
