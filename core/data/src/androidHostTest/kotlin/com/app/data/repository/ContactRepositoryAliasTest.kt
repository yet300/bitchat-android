@file:OptIn(ExperimentalCoroutinesApi::class)

package com.app.data.repository

import com.app.crypto.identity.PeerFingerprintManager
import com.app.data.favorites.FavoritesPersistenceService
import com.app.domain.model.PeerId
import com.app.domain.repository.IdentityRepository
import com.app.data.routing.PeerAddressResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    private lateinit var resolver: PeerAddressResolver
    private lateinit var repository: ContactRepositoryImpl

    @Before
    fun setUp() {
        favorites = mock()
        resolver = mock()
        repository = ContactRepositoryImpl(
            contactDao = InMemoryDatabase().contactDao,
            favorites = favorites,
            fingerprints = PeerFingerprintManager(),
            peerAddressResolver = resolver,
            identityRepository = mock<IdentityRepository>(),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
    }

    @Test
    fun delegatesAliasResolutionToPeerAddressResolver() = runTest {
        whenever(resolver.noiseKeyHexForNostrAlias(ALIAS)).thenReturn(NOISE_KEY_HEX)

        assertEquals(NOISE_KEY_HEX, repository.noiseKeyHexForNostrAlias(PeerId(ALIAS)))
    }

    @Test
    fun unknownAliasReturnsNull() = runTest {
        whenever(resolver.noiseKeyHexForNostrAlias(ALIAS)).thenReturn(null)

        assertNull(repository.noiseKeyHexForNostrAlias(PeerId(ALIAS)))
    }
}
