@file:OptIn(ExperimentalTime::class)

package com.app.domain.usecase

import com.app.domain.FakeContactRepository
import com.app.domain.FakePeerRepository
import com.app.domain.model.Contact
import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.PeerIdentity
import com.app.domain.model.Reachability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class ResolveReachabilityUseCaseTest {

    private val noiseHex = "a".repeat(64)
    private val epoch = Instant.fromEpochMilliseconds(0)

    private fun peer(id: String, connected: Boolean = true, noiseKeyHex: String? = null) =
        Peer(id = PeerId(id), nickname = "n", isConnected = connected, isDirect = true, noiseKeyHex = noiseKeyHex)

    private fun mutualContact(hex: String) = Contact(
        identity = PeerIdentity(hex),
        nickname = "fav",
        isFavorite = true,
        theyFavoritedUs = true,
        favoritedAt = epoch,
        lastUpdated = epoch,
    )

    private fun useCase(
        peers: List<Peer> = emptyList(),
        aliasToNoiseHex: Map<String, String> = emptyMap(),
        contactsByNoiseHex: Map<String, Contact> = emptyMap(),
    ) = ResolveReachabilityUseCase(
        peers = FakePeerRepository(peers),
        contacts = FakeContactRepository(
            aliasToNoiseHex = aliasToNoiseHex,
            contactsByNoiseHex = contactsByNoiseHex,
        ),
    )

    @Test
    fun public_mesh_nearby_when_any_peer_connected() = runTest {
        val uc = useCase(peers = listOf(peer("1111111111111111")))
        assertEquals(Reachability.NEARBY, uc.observe(ConversationId.PublicMesh).first())
    }

    @Test
    fun public_mesh_offline_when_no_peers() = runTest {
        assertEquals(Reachability.OFFLINE, useCase().observe(ConversationId.PublicMesh).first())
    }

    @Test
    fun channel_follows_mesh_connection() = runTest {
        val uc = useCase(peers = listOf(peer("1111111111111111")))
        assertEquals(Reachability.NEARBY, uc.observe(ConversationId.Channel("dev")).first())
    }

    @Test
    fun geohash_is_internet() = runTest {
        val id = ConversationId.Geohash(GeohashChannel(GeohashLevel.CITY, "u"))
        assertEquals(Reachability.INTERNET, useCase().observe(id).first())
    }

    @Test
    fun private_ephemeral_nearby_when_connected() = runTest {
        val id = ConversationId.Private(PeerId("1111111111111111"))
        val uc = useCase(peers = listOf(peer("1111111111111111")))
        assertEquals(Reachability.NEARBY, uc.observe(id).first())
    }

    @Test
    fun private_ephemeral_offline_when_absent() = runTest {
        val id = ConversationId.Private(PeerId("1111111111111111"))
        assertEquals(Reachability.OFFLINE, useCase().observe(id).first())
    }

    @Test
    fun private_noise_stable_nearby_when_matching_peer_connected() = runTest {
        val id = ConversationId.Private(PeerId(noiseHex))
        val uc = useCase(peers = listOf(peer("2222222222222222", noiseKeyHex = noiseHex)))
        assertEquals(Reachability.NEARBY, uc.observe(id).first())
    }

    @Test
    fun private_noise_stable_internet_when_mutual_and_offline() = runTest {
        val id = ConversationId.Private(PeerId(noiseHex))
        val uc = useCase(contactsByNoiseHex = mapOf(noiseHex to mutualContact(noiseHex)))
        assertEquals(Reachability.INTERNET, uc.observe(id).first())
    }

    @Test
    fun private_noise_stable_offline_when_not_mutual() = runTest {
        val id = ConversationId.Private(PeerId(noiseHex))
        assertEquals(Reachability.OFFLINE, useCase().observe(id).first())
    }

    @Test
    fun private_nostr_alias_internet_via_resolved_noise_key() = runTest {
        val alias = "nostr_0123456789abcdef"
        val id = ConversationId.Private(PeerId(alias))
        val uc = useCase(
            aliasToNoiseHex = mapOf(alias to noiseHex),
            contactsByNoiseHex = mapOf(noiseHex to mutualContact(noiseHex)),
        )
        assertEquals(Reachability.INTERNET, uc.observe(id).first())
    }

    @Test
    fun private_prefers_nearby_over_internet() = runTest {
        // Connected matching peer AND mutual favorite -> NEARBY wins (cheaper route).
        val id = ConversationId.Private(PeerId(noiseHex))
        val uc = useCase(
            peers = listOf(peer("3333333333333333", noiseKeyHex = noiseHex)),
            contactsByNoiseHex = mapOf(noiseHex to mutualContact(noiseHex)),
        )
        assertEquals(Reachability.NEARBY, uc.observe(id).first())
    }
}
