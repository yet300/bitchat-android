@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package com.yet.bitmessage.feature.chats.conversations.settings.store

import com.app.domain.model.BitMessage
import com.app.domain.model.Contact
import com.app.domain.model.ConversationId
import com.app.domain.model.DeliveryStatus
import com.app.domain.model.Fingerprint
import com.app.domain.model.MyIdentity
import com.app.domain.model.PeerId
import com.app.domain.model.PeerIdentity
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.SettingsRepository
import com.app.domain.usecase.PanicWipeUseCase
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

class SettingsStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeSettingsRepository : SettingsRepository {
        val nickname = MutableStateFlow("alice")
        override fun observeNickname(): Flow<String> = nickname
        override suspend fun setNickname(value: String) { nickname.value = value }
        override var locationServicesEnabled: Boolean = true
    }

    private class FakeIdentityRepository : IdentityRepository {
        var wiped = false
        override suspend fun myIdentity(): MyIdentity = MyIdentity(
            peerId = PeerId("a".repeat(64)),
            fingerprint = Fingerprint("fp"),
            nickname = "alice",
            nostrNpub = "npub1xyz",
        )
        override fun observeVerifiedFingerprints(): Flow<Set<Fingerprint>> = flowOf(emptySet())
        override suspend fun isVerified(fingerprint: Fingerprint): Boolean = false
        override suspend fun setVerified(fingerprint: Fingerprint, verified: Boolean) = Unit
        override suspend fun panicWipe() { wiped = true }
    }

    private class FakeMessageRepository : MessageRepository {
        var cleared = false
        override fun observeMessages(id: ConversationId): Flow<List<BitMessage>> = flowOf(emptyList())
        override suspend fun snapshot(id: ConversationId): List<BitMessage> = emptyList()
        override suspend fun append(id: ConversationId, message: BitMessage) = Unit
        override suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus) = Unit
        override suspend fun remove(messageId: String) = Unit
        override suspend fun clear(id: ConversationId) = Unit
        override suspend fun clearAll() { cleared = true }
    }

    private class FakeContactRepository : ContactRepository {
        var cleared = false
        override fun observeFavorites(): Flow<Set<Fingerprint>> = flowOf(emptySet())
        override fun observeContacts(): Flow<List<Contact>> = flowOf(emptyList())
        override suspend fun toggleFavorite(peer: PeerId) = Unit
        override suspend fun isFavorite(peer: PeerId): Boolean = false
        override suspend fun setBlocked(peer: PeerId, blocked: Boolean) = Unit
        override suspend fun isBlocked(peer: PeerId): Boolean = false
        override suspend fun contact(identity: PeerIdentity): Contact? = null
        override suspend fun noiseKeyHexForNostrAlias(alias: PeerId): String? = null
        override suspend fun clearAll() { cleared = true }
    }

    private fun factory(
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        identity: FakeIdentityRepository = FakeIdentityRepository(),
        messages: FakeMessageRepository = FakeMessageRepository(),
        contacts: FakeContactRepository = FakeContactRepository(),
    ) = SettingsStoreFactory(
        storeFactory = DefaultStoreFactory(),
        settingsRepository = settings,
        identityRepository = identity,
        panicWipe = PanicWipeUseCase(messages, contacts, identity),
    )

    @Test
    fun loads_nickname_and_identity() = runTest {
        val store = factory().create()
        assertEquals("alice", store.state.nickname)
        assertEquals("npub1xyz", store.state.npub)
        assertEquals("fp", store.state.fingerprint)
    }

    @Test
    fun nickname_change_persists() = runTest {
        val settings = FakeSettingsRepository()
        val store = factory(settings = settings).create()

        store.accept(SettingsStore.Intent.NicknameChanged("bob"))

        assertEquals("bob", settings.nickname.value)
        assertEquals("bob", store.state.nickname)
    }

    @Test
    fun panic_wipe_clears_messages_contacts_and_identity() = runTest {
        val messages = FakeMessageRepository()
        val contacts = FakeContactRepository()
        val identity = FakeIdentityRepository()
        val store = factory(messages = messages, contacts = contacts, identity = identity).create()

        store.accept(SettingsStore.Intent.PanicWipe)

        assertTrue(messages.cleared)
        assertTrue(contacts.cleared)
        assertTrue(identity.wiped)
        assertEquals(false, store.state.isWiping)
    }
}
