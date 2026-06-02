package com.app.domain.usecase

import com.app.domain.FakeContactRepository
import com.app.domain.FakeIdentityRepository
import com.app.domain.FakeMessageRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class PanicWipeUseCaseTest {

    @Test fun `clears messages contacts and identity`() = runTest {
        val messages = FakeMessageRepository()
        val contacts = FakeContactRepository()
        val identity = FakeIdentityRepository()

        PanicWipeUseCase(messages, contacts, identity).invoke()

        assertTrue(messages.clearedAll)
        assertTrue(contacts.clearedAll)
        assertTrue(identity.panicWiped)
    }
}
