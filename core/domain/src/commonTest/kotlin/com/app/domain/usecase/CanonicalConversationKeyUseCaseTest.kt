package com.app.domain.usecase

import com.app.domain.FakeContactRepository
import com.app.domain.FakePeerRepository
import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalConversationKeyUseCaseTest {

    private val noiseHex = "aa".repeat(32) // 64 hex → NOISE_STABLE
    private val meshId = PeerId("1234567890abcdef") // 16 hex → MESH_EPHEMERAL
    private val meshPeer = Peer(
        id = meshId,
        nickname = "bob",
        isConnected = true,
        isDirect = true,
        noiseKeyHex = noiseHex,
    )

    private fun useCase(
        peers: List<Peer> = emptyList(),
        aliasToNoiseHex: Map<String, String> = emptyMap(),
    ) = CanonicalConversationKeyUseCase(
        FakePeerRepository(peers),
        FakeContactRepository(aliasToNoiseHex = aliasToNoiseHex),
    )

    @Test fun `mesh ephemeral private canonicalizes to noise key`() = runTest {
        val canonical = useCase(peers = listOf(meshPeer))(ConversationId.Private(meshId))
        assertEquals(ConversationId.Private(PeerId(noiseHex)), canonical)
    }

    @Test fun `mesh ephemeral private without resolvable peer falls back to raw id`() = runTest {
        val original = ConversationId.Private(meshId)
        assertEquals(original, useCase()(original))
    }

    @Test fun `noise stable private is already canonical`() = runTest {
        val original = ConversationId.Private(PeerId(noiseHex))
        // Even with a live peer present, a stable key must not be re-resolved.
        assertEquals(original, useCase(peers = listOf(meshPeer))(original))
    }

    @Test fun `nostr alias private canonicalizes via contacts`() = runTest {
        val alias = PeerId("nostr_deadbeef")
        val canonical = useCase(aliasToNoiseHex = mapOf(alias.raw to noiseHex))(ConversationId.Private(alias))
        assertEquals(ConversationId.Private(PeerId(noiseHex)), canonical)
    }

    @Test fun `nostr alias private with unknown mapping falls back to raw id`() = runTest {
        val original = ConversationId.Private(PeerId("nostr_unknown"))
        assertEquals(original, useCase()(original))
    }

    @Test fun `resolved noise key is lowercased for stable comparison`() = runTest {
        val upper = "AA".repeat(32)
        val peer = meshPeer.copy(noiseKeyHex = upper)
        val canonical = useCase(peers = listOf(peer))(ConversationId.Private(meshId))
        assertEquals(ConversationId.Private(PeerId(noiseHex)), canonical)
    }

    @Test fun `non private ids are returned unchanged`() = runTest {
        val uc = useCase(peers = listOf(meshPeer))
        assertEquals(ConversationId.PublicMesh, uc(ConversationId.PublicMesh))
        val channel = ConversationId.Channel("#dev")
        assertEquals(channel, uc(channel))
        val geo = ConversationId.Geohash(GeohashChannel(GeohashLevel.CITY, "u4pruyd"))
        assertEquals(geo, uc(geo))
    }
}
