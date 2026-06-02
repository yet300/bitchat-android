package com.bitchat.android.core.domain.usecase

import com.bitchat.android.core.domain.FakeContactRepository
import com.bitchat.android.core.domain.FakePeerRepository
import com.bitchat.android.core.domain.model.Peer
import com.bitchat.android.core.domain.model.PeerId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveConversationUseCaseTest {

    private val noiseHex = "aa".repeat(32) // 64 hex → NOISE_STABLE
    private val meshId = PeerId("1234567890abcdef") // 16 hex → MESH_EPHEMERAL
    private val connectedPeer = Peer(
        id = meshId,
        nickname = "bob",
        isConnected = true,
        isDirect = true,
        noiseKeyHex = noiseHex,
    )

    @Test fun `mesh ephemeral returns itself`() = runTest {
        val resolve = ResolveConversationUseCase(FakePeerRepository(), FakeContactRepository())
        assertEquals(meshId, resolve(meshId))
    }

    @Test fun `noise stable resolves to connected mesh peer`() = runTest {
        val resolve = ResolveConversationUseCase(FakePeerRepository(listOf(connectedPeer)), FakeContactRepository())
        assertEquals(meshId, resolve(PeerId(noiseHex)))
    }

    @Test fun `noise stable without live peer stays as is`() = runTest {
        val resolve = ResolveConversationUseCase(FakePeerRepository(), FakeContactRepository())
        val target = PeerId(noiseHex)
        assertEquals(target, resolve(target))
    }

    @Test fun `nostr alias resolves via contacts then peer`() = runTest {
        val alias = PeerId("nostr_deadbeef")
        val resolve = ResolveConversationUseCase(
            FakePeerRepository(listOf(connectedPeer)),
            FakeContactRepository(aliasToNoiseHex = mapOf(alias.raw to noiseHex)),
        )
        assertEquals(meshId, resolve(alias))
    }

    @Test fun `nostr alias with unknown mapping stays as is`() = runTest {
        val alias = PeerId("nostr_unknown")
        val resolve = ResolveConversationUseCase(FakePeerRepository(listOf(connectedPeer)), FakeContactRepository())
        assertEquals(alias, resolve(alias))
    }
}
