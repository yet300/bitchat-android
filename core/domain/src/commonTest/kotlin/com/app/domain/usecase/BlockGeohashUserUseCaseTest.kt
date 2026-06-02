package com.app.domain.usecase

import com.app.domain.FakeGeohashRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockGeohashUserUseCaseTest {

    private val pubkey = "abcdef0123456789"

    @Test fun `blocks resolved nickname`() = runTest {
        val repo = FakeGeohashRepository(nicknameToPubkey = mapOf("alice" to pubkey))
        val result = BlockGeohashUserUseCase(repo).invoke("alice")

        assertTrue(result)
        assertTrue(pubkey in repo.blocked)
    }

    @Test fun `unknown nickname is not blocked`() = runTest {
        val repo = FakeGeohashRepository()
        val result = BlockGeohashUserUseCase(repo).invoke("ghost")

        assertFalse(result)
        assertTrue(repo.blocked.isEmpty())
    }
}
