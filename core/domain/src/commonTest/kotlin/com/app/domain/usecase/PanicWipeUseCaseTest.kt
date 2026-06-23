package com.app.domain.usecase

import com.app.domain.FakeContactRepository
import com.app.domain.FakeDatabasePanicWiper
import com.app.domain.FakeIdentityRepository
import com.app.domain.FakeMediaCleaner
import com.app.domain.FakeMessageRepository
import com.app.domain.FakeMeshResetPort
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanicWipeUseCaseTest {

    private fun useCase(
        messages: FakeMessageRepository = FakeMessageRepository(),
        contacts: FakeContactRepository = FakeContactRepository(),
        identity: FakeIdentityRepository = FakeIdentityRepository(),
        meshReset: FakeMeshResetPort = FakeMeshResetPort(),
        mediaCleaner: FakeMediaCleaner = FakeMediaCleaner(),
        databaseWiper: FakeDatabasePanicWiper = FakeDatabasePanicWiper(),
    ) = PanicWipeUseCase(messages, contacts, identity, meshReset, mediaCleaner, databaseWiper)

    @Test fun `clears messages contacts and identity`() = runTest {
        val messages = FakeMessageRepository()
        val contacts = FakeContactRepository()
        val identity = FakeIdentityRepository()

        useCase(messages = messages, contacts = contacts, identity = identity).invoke()

        assertTrue(messages.clearedAll)
        assertTrue(contacts.clearedAll)
        assertTrue(identity.panicWiped)
    }

    @Test fun `resets mesh identity after clearing stores`() = runTest {
        val meshReset = FakeMeshResetPort()
        useCase(meshReset = meshReset).invoke()
        assertEquals(1, meshReset.resetCount, "mesh must be reset exactly once during panic wipe")
    }

    @Test fun `wipes media files after clearing stores`() = runTest {
        val mediaCleaner = FakeMediaCleaner()
        useCase(mediaCleaner = mediaCleaner).invoke()
        assertTrue(mediaCleaner.wiped, "media files must be deleted during panic wipe")
    }

    @Test fun `crypto-erases the database`() = runTest {
        val databaseWiper = FakeDatabasePanicWiper()
        useCase(databaseWiper = databaseWiper).invoke()
        assertTrue(databaseWiper.wiped, "the encrypted database must be crypto-erased during panic wipe")
    }
}
