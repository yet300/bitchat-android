package com.app.data.routing

import com.app.data.favorites.FavoritesPersistenceService
import com.app.data.favorites.FavoriteRelationship
import com.app.transport.mesh.MeshService
import com.app.transport.mesh.PeerInfo
import com.app.transport.nostr.GeohashAliasRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class PeerAddressResolverTest {

    private companion object {
        const val ALIAS = "nostr_1234abcd1234abcd"
        const val MESH_PEER = "aabbccdd11223344"
        val NOSTR_PUBKEY_HEX = "ab".repeat(32)
        val NOISE_KEY = ByteArray(32) { (it + 1).toByte() }
        val NOISE_KEY_HEX = NOISE_KEY.joinToString("") { "%02x".format(it) }
    }

    private lateinit var mesh: MeshService
    private lateinit var registry: GeohashAliasRegistry
    private lateinit var favorites: FavoritesPersistenceService
    private lateinit var resolver: PeerAddressResolver

    @Before
    fun setUp() {
        mesh = mock()
        registry = mock()
        favorites = mock()
        resolver = PeerAddressResolver(mesh, registry, favorites)
    }

    private fun relationship(isMutual: Boolean, npub: String?) = FavoriteRelationship(
        peerNoisePublicKey = NOISE_KEY,
        peerNostrPublicKey = npub,
        peerNickname = "nick",
        isFavorite = true,
        theyFavoritedUs = isMutual,
        favoritedAt = Clock.System.now(),
        lastUpdated = Clock.System.now(),
    )

    @Test
    fun aliasResolvesThroughRegistry() {
        whenever(registry.contains(ALIAS)).thenReturn(true)
        whenever(registry.get(ALIAS)).thenReturn(NOSTR_PUBKEY_HEX)

        assertEquals(NOSTR_PUBKEY_HEX, resolver.nostrPubkeyHexForAlias(ALIAS))
    }

    @Test
    fun nonAliasInputReturnsNull() {
        assertNull(resolver.nostrPubkeyHexForAlias(MESH_PEER))
        assertNull(resolver.nostrPubkeyHexForAlias(NOISE_KEY_HEX))
    }

    @Test
    fun noiseKeyBridgeGoesThroughFavorites() {
        whenever(registry.contains(ALIAS)).thenReturn(true)
        whenever(registry.get(ALIAS)).thenReturn(NOSTR_PUBKEY_HEX)
        whenever(favorites.findNoiseKey(NOSTR_PUBKEY_HEX)).thenReturn(NOISE_KEY)

        assertEquals(NOISE_KEY_HEX, resolver.noiseKeyHexForNostrAlias(ALIAS))
    }

    @Test
    fun canSendViaNostrDispatchesByPeerKind() {
        // 64-hex stable Noise key → ByteArray overload
        whenever(favorites.getFavoriteStatus(any<ByteArray>()))
            .thenReturn(relationship(isMutual = true, npub = "npub1xyz"))
        assertTrue(resolver.canSendViaNostr(NOISE_KEY_HEX))

        // 16-hex mesh ephemeral → String overload
        whenever(favorites.getFavoriteStatus(MESH_PEER))
            .thenReturn(relationship(isMutual = true, npub = "npub1xyz"))
        assertTrue(resolver.canSendViaNostr(MESH_PEER))

        // nostr_ alias is never directly Nostr-sendable (strategies resolve it separately)
        assertFalse(resolver.canSendViaNostr(ALIAS))
    }

    @Test
    fun canSendViaNostrRequiresMutualAndNpub() {
        whenever(favorites.getFavoriteStatus(MESH_PEER))
            .thenReturn(relationship(isMutual = false, npub = "npub1xyz"))
        assertFalse(resolver.canSendViaNostr(MESH_PEER))

        whenever(favorites.getFavoriteStatus(MESH_PEER))
            .thenReturn(relationship(isMutual = true, npub = null))
        assertFalse(resolver.canSendViaNostr(MESH_PEER))
    }

    @Test
    fun canonicalPeerIDUpgradesNoiseHexToConnectedMeshPeer() {
        whenever(mesh.getPeerInfo(MESH_PEER)).thenReturn(
            PeerInfo(
                id = MESH_PEER, nickname = "nick", isConnected = true,
                isDirectConnection = true, noisePublicKey = NOISE_KEY,
                signingPublicKey = ByteArray(32), isVerifiedNickname = false,
                lastSeen = 0L,
            ),
        )

        assertEquals(MESH_PEER, resolver.canonicalPeerID(NOISE_KEY_HEX, listOf(MESH_PEER)))
        // No connected peer holding the key → stays the stable hex
        assertEquals(NOISE_KEY_HEX, resolver.canonicalPeerID(NOISE_KEY_HEX, emptyList()))
    }

    @Test
    fun canonicalPeerIDResolvesAliasToMeshPeerOrNoiseHex() {
        whenever(registry.contains(ALIAS)).thenReturn(true)
        whenever(registry.get(ALIAS)).thenReturn(NOSTR_PUBKEY_HEX)
        whenever(favorites.findNoiseKey(NOSTR_PUBKEY_HEX)).thenReturn(NOISE_KEY)
        whenever(mesh.getPeerInfo(MESH_PEER)).thenReturn(
            PeerInfo(
                id = MESH_PEER, nickname = "nick", isConnected = true,
                isDirectConnection = true, noisePublicKey = NOISE_KEY,
                signingPublicKey = ByteArray(32), isVerifiedNickname = false,
                lastSeen = 0L,
            ),
        )

        assertEquals(MESH_PEER, resolver.canonicalPeerID(ALIAS, listOf(MESH_PEER)))
        assertEquals(NOISE_KEY_HEX, resolver.canonicalPeerID(ALIAS, emptyList()))
        // Unresolvable alias stays itself
        whenever(registry.contains(ALIAS)).thenReturn(false)
        whenever(favorites.getOurFavorites()).thenReturn(emptyList())
        assertEquals(ALIAS, resolver.canonicalPeerID(ALIAS, listOf(MESH_PEER)))
    }

    @Test
    fun meshEphemeralPassesThroughUnchanged() {
        assertEquals(MESH_PEER, resolver.canonicalPeerID(MESH_PEER, listOf(MESH_PEER)))
    }
}
