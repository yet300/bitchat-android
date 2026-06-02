package com.app.domain.usecase

import com.app.domain.FakeIdentityRepository
import com.app.domain.FakePeerRepository
import com.app.domain.model.Fingerprint
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsPeerVerifiedUseCaseTest {

    private val fp = Fingerprint("f".repeat(64))
    private val meshId = PeerId("1234567890abcdef")
    private val peer = Peer(meshId, "bob", isConnected = true, isDirect = true, fingerprint = fp)

    @Test fun `nostr alias is never verified`() = runTest {
        val useCase = IsPeerVerifiedUseCase(FakePeerRepository(), FakeIdentityRepository(setOf(fp)))
        assertFalse(useCase(PeerId("nostr_deadbeef")))
    }

    @Test fun `mesh peer with verified fingerprint`() = runTest {
        val useCase = IsPeerVerifiedUseCase(FakePeerRepository(listOf(peer)), FakeIdentityRepository(setOf(fp)))
        assertTrue(useCase(meshId))
    }

    @Test fun `mesh peer with unverified fingerprint`() = runTest {
        val useCase = IsPeerVerifiedUseCase(FakePeerRepository(listOf(peer)), FakeIdentityRepository())
        assertFalse(useCase(meshId))
    }

    @Test fun `unknown peer is not verified`() = runTest {
        val useCase = IsPeerVerifiedUseCase(FakePeerRepository(), FakeIdentityRepository(setOf(fp)))
        assertFalse(useCase(meshId))
    }
}
