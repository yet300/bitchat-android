@file:OptIn(ExperimentalCoroutinesApi::class)

package com.app.data.repository

import com.app.crypto.identity.PeerFingerprintManager
import com.app.data.favorites.FavoritesPersistenceService
import com.app.data.routing.PeerAddressResolver
import com.app.domain.model.PeerId
import com.app.domain.repository.IdentityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Favorites + block round-trip through the encrypted DB. The repo no longer depends on SettingsStore
 * at all, so nothing sensitive can land in plaintext prefs by construction. Uses a 64-hex (NOISE_STABLE)
 * peer so the fingerprint derives directly without the live mesh mapping.
 */
class ContactRepositoryFavoritesTest {

    private val peer = PeerId("aa".repeat(32))

    private fun repo(db: InMemoryDatabase) = ContactRepositoryImpl(
        contactDao = db.contactDao,
        favorites = mock<FavoritesPersistenceService>(),
        fingerprints = PeerFingerprintManager(),
        peerAddressResolver = mock<PeerAddressResolver>(),
        identityRepository = mock<IdentityRepository>(),
        scope = CoroutineScope(UnconfinedTestDispatcher()),
    )

    @Test
    fun favorite_toggles_through_the_db() = runTest {
        val repo = repo(InMemoryDatabase())
        assertFalse(repo.isFavorite(peer))

        repo.toggleFavorite(peer)
        assertTrue(repo.isFavorite(peer))
        assertEquals(1, repo.observeFavorites().first().size)

        repo.toggleFavorite(peer)
        assertFalse(repo.isFavorite(peer))
        assertTrue(repo.observeFavorites().first().isEmpty())
    }

    @Test
    fun block_is_visible_synchronously_after_set() = runTest {
        val repo = repo(InMemoryDatabase())
        assertFalse(repo.isBlocked(peer))

        repo.setBlocked(peer, blocked = true)
        // isBlocked is the synchronous hot-path read; the write-through mirror must already reflect it.
        assertTrue(repo.isBlocked(peer))

        repo.setBlocked(peer, blocked = false)
        assertFalse(repo.isBlocked(peer))
    }

    @Test
    fun favorite_and_block_coexist_on_one_fingerprint() = runTest {
        val repo = repo(InMemoryDatabase())
        repo.toggleFavorite(peer)
        repo.setBlocked(peer, blocked = true)

        assertTrue(repo.isFavorite(peer))
        assertTrue(repo.isBlocked(peer))
    }
}
