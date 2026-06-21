@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package com.yet.bitmessage.feature.chats.conversations.contacts.store

import com.app.domain.model.Contact
import com.app.domain.model.Fingerprint
import com.app.domain.model.PeerId
import com.app.domain.model.PeerIdentity
import com.app.domain.repository.ContactRepository
import com.yet.bitmessage.feature.chats.conversations.contacts.contactsStateToModel
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class ContactsStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeContactRepository(initial: List<Contact>) : ContactRepository {
        val contacts = MutableStateFlow(initial)
        val blockCalls = mutableListOf<Pair<String, Boolean>>()
        override fun observeFavorites(): Flow<Set<Fingerprint>> = MutableStateFlow(emptySet())
        override fun observeContacts(): Flow<List<Contact>> = contacts
        override fun observeVerified(noiseKeyHex: String): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun toggleFavorite(peer: PeerId) = Unit
        override suspend fun isFavorite(peer: PeerId): Boolean = false
        override suspend fun setBlocked(peer: PeerId, blocked: Boolean) {
            blockCalls += peer.raw to blocked
        }
        override fun isBlocked(peer: PeerId): Boolean = false
        override suspend fun contact(identity: PeerIdentity): Contact? = null
        override suspend fun noiseKeyHexForNostrAlias(alias: PeerId): String? = null
        override suspend fun clearAll() = Unit
    }

    private fun contact(name: String, blocked: Boolean = false, verified: Boolean = false) = Contact(
        identity = PeerIdentity(noiseKeyHex = name.repeat(64).take(64)),
        nickname = name,
        isFavorite = true,
        theyFavoritedUs = false,
        favoritedAt = Instant.fromEpochMilliseconds(0),
        lastUpdated = Instant.fromEpochMilliseconds(0),
        isBlocked = blocked,
        isVerified = verified,
    )

    @Test
    fun splits_contacts_into_favorites_and_blocked() = runTest {
        val repo = FakeContactRepository(listOf(contact("a"), contact("b", blocked = true)))
        val store = ContactsStoreFactory(DefaultStoreFactory(), repo).create()

        val model = contactsStateToModel(store.state)
        assertEquals(listOf("a"), model.favorites.map { it.name })
        assertEquals(listOf("b"), model.blocked.map { it.name })
    }

    @Test
    fun verified_flag_propagates_to_the_row() = runTest {
        val repo = FakeContactRepository(listOf(contact("a", verified = true)))
        val store = ContactsStoreFactory(DefaultStoreFactory(), repo).create()

        assertTrue(contactsStateToModel(store.state).favorites.single().isVerified)
    }

    @Test
    fun set_blocked_routes_to_repository() = runTest {
        val repo = FakeContactRepository(listOf(contact("a")))
        val store = ContactsStoreFactory(DefaultStoreFactory(), repo).create()

        store.accept(ContactsStore.Intent.SetBlocked("a".repeat(64), blocked = true))

        assertTrue(repo.blockCalls.any { it.second })
    }
}
