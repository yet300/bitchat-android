package com.app.domain.usecase

import com.app.domain.FakeMessageRepository
import com.app.domain.FakeMessageTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CancelTransferUseCaseTest {

    @Test fun `removes message when transport cancels`() = runTest {
        val transport = FakeMessageTransport().apply { cancelResult = true }
        val repo = FakeMessageRepository()
        val result = CancelTransferUseCase(transport, repo).invoke("MSG-1")

        assertTrue(result)
        assertEquals(listOf("MSG-1"), repo.removed)
    }

    @Test fun `keeps message when transport does not cancel`() = runTest {
        val transport = FakeMessageTransport().apply { cancelResult = false }
        val repo = FakeMessageRepository()
        val result = CancelTransferUseCase(transport, repo).invoke("MSG-1")

        assertFalse(result)
        assertTrue(repo.removed.isEmpty())
    }
}
