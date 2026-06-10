package com.app.data.repository

import com.app.crypto.identity.PeerFingerprintManager
import com.app.data.favorites.FavoritesPersistenceService
import com.app.domain.model.PeerId
import com.app.domain.repository.SettingsStore
import com.app.transport.nostr.GeohashAliasRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ContactRepositoryAliasTest {

    private companion object {
        const val ALIAS = "nostr_1234abcd1234abcd"
        val NOSTR_PUBKEY_HEX = "ab".repeat(32)
        val NOISE_KEY = ByteArray(32) { (it + 1).toByte() }
        val NOISE_KEY_HEX = NOISE_KEY.joinToString("") { "%02x".format(it) }
    }

    private lateinit var favorites: FavoritesPersistenceService
    private lateinit var aliasRegistry: GeohashAliasRegistry
    private lateinit var repository: ContactRepositoryImpl

    @Before
    fun setUp() {
        favorites = mock()
        aliasRegistry = mock()
        repository = ContactRepositoryImpl(
            settings = mock<SettingsStore>(),
            favorites = favorites,
            fingerprints = PeerFingerprintManager(),
            geohashAliasRegistry = aliasRegistry,
        )
    }

    @Test
    fun resolvesAliasThroughRegistryAndFavorites() = runTest {
        whenever(aliasRegistry.get(ALIAS)).thenReturn(NOSTR_PUBKEY_HEX)
        whenever(favorites.findNoiseKey(NOSTR_PUBKEY_HEX)).thenReturn(NOISE_KEY)

        assertEquals(NOISE_KEY_HEX, repository.noiseKeyHexForNostrAlias(PeerId(ALIAS)))
    }

    @Test
    fun unknownAliasReturnsNull() = runTest {
        whenever(aliasRegistry.get(ALIAS)).thenReturn(null)

        assertNull(repository.noiseKeyHexForNostrAlias(PeerId(ALIAS)))
    }

    @Test
    fun aliasWithoutFavoriteMappingReturnsNull() = runTest {
        whenever(aliasRegistry.get(ALIAS)).thenReturn(NOSTR_PUBKEY_HEX)
        whenever(favorites.findNoiseKey(NOSTR_PUBKEY_HEX)).thenReturn(null)

        assertNull(repository.noiseKeyHexForNostrAlias(PeerId(ALIAS)))
    }
}
