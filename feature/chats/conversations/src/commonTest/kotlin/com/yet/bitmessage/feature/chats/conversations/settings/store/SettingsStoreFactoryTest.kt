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
import com.app.domain.model.ThemeMode
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MeshSettingsRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.NotificationPermissionRepository
import com.app.domain.repository.NotificationSettingsRepository
import com.app.domain.repository.PowDifficultyLevel
import com.app.domain.repository.PowRepository
import com.app.domain.repository.SettingsRepository
import com.app.domain.repository.ThemeRepository
import com.app.domain.repository.TorRepository
import com.app.domain.repository.VerificationRepository
import com.app.domain.usecase.PanicWipeUseCase
import com.yet.bitmessage.feature.chats.conversations.settings.NotifPermissionStatus
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
        override fun observeVerified(noiseKeyHex: String): Flow<Boolean> = flowOf(false)
        override suspend fun toggleFavorite(peer: PeerId) = Unit
        override suspend fun isFavorite(peer: PeerId): Boolean = false
        override suspend fun setBlocked(peer: PeerId, blocked: Boolean) = Unit
        override fun isBlocked(peer: PeerId): Boolean = false
        override suspend fun contact(identity: PeerIdentity): Contact? = null
        override suspend fun noiseKeyHexForNostrAlias(alias: PeerId): String? = null
        override suspend fun clearAll() { cleared = true }
    }

    private class FakeThemeRepository : ThemeRepository {
        val mode = MutableStateFlow(ThemeMode.SYSTEM)
        override fun observeTheme(): Flow<ThemeMode> = mode
        override suspend fun setTheme(mode: ThemeMode) { this.mode.value = mode }
    }

    private class FakeTorRepository : TorRepository {
        val enabled = MutableStateFlow(false)
        override fun observeTorEnabled(): Flow<Boolean> = enabled
        override suspend fun setTorEnabled(enabled: Boolean) { this.enabled.value = enabled }
    }

    private class FakePowRepository : PowRepository {
        val enabled = MutableStateFlow(false)
        val difficulty = MutableStateFlow(8)
        override fun observePowEnabled(): Flow<Boolean> = enabled
        override fun observePowDifficulty(): Flow<Int> = difficulty
        override suspend fun setPowEnabled(enabled: Boolean) { this.enabled.value = enabled }
        override suspend fun setPowDifficulty(difficulty: Int) { this.difficulty.value = difficulty }
        override fun difficultyLevels(): List<PowDifficultyLevel> =
            listOf(PowDifficultyLevel(8, "Low"), PowDifficultyLevel(16, "High"))
    }

    private class FakeMeshSettingsRepository : MeshSettingsRepository {
        val autoStart = MutableStateFlow(true)
        val background = MutableStateFlow(true)
        override fun observeAutoStart(): Flow<Boolean> = autoStart
        override fun observeBackgroundEnabled(): Flow<Boolean> = background
        override suspend fun setAutoStart(enabled: Boolean) { autoStart.value = enabled }
        override suspend fun setBackgroundEnabled(enabled: Boolean) { background.value = enabled }
    }

    private class FakeNotificationSettingsRepository : NotificationSettingsRepository {
        val globalMute = MutableStateFlow(false)
        override fun observeGlobalMuteEnabled(): Flow<Boolean> = globalMute
        override suspend fun setGlobalMuteEnabled(enabled: Boolean) { globalMute.value = enabled }
    }

    private class FakeNotificationPermissionRepository : NotificationPermissionRepository {
        val granted = MutableStateFlow(false)
        var requested = false
        override fun observeGranted(): Flow<Boolean> = granted
        override suspend fun requestPermission() { requested = true }
    }

    private class FakeVerificationRepository(
        private val qr: String? = "bitchat://verify?x=1",
    ) : VerificationRepository {
        var built = false
        override suspend fun buildMyVerificationQr(): String? {
            built = true
            return qr
        }
    }

    private fun factory(
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        identity: FakeIdentityRepository = FakeIdentityRepository(),
        messages: FakeMessageRepository = FakeMessageRepository(),
        contacts: FakeContactRepository = FakeContactRepository(),
        theme: FakeThemeRepository = FakeThemeRepository(),
        tor: FakeTorRepository = FakeTorRepository(),
        pow: FakePowRepository = FakePowRepository(),
        mesh: FakeMeshSettingsRepository = FakeMeshSettingsRepository(),
        notifSettings: FakeNotificationSettingsRepository = FakeNotificationSettingsRepository(),
        notifPermission: FakeNotificationPermissionRepository = FakeNotificationPermissionRepository(),
        verification: FakeVerificationRepository = FakeVerificationRepository(),
    ) = SettingsStoreFactory(
        storeFactory = DefaultStoreFactory(),
        settingsRepository = settings,
        identityRepository = identity,
        themeRepository = theme,
        torRepository = tor,
        powRepository = pow,
        meshSettingsRepository = mesh,
        notificationSettingsRepository = notifSettings,
        notificationPermissionRepository = notifPermission,
        verificationRepository = verification,
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

    @Test
    fun loads_theme_tor_and_pow_and_difficulty_levels() = runTest {
        val theme = FakeThemeRepository().apply { mode.value = ThemeMode.DARK }
        val tor = FakeTorRepository().apply { enabled.value = true }
        val pow = FakePowRepository().apply { enabled.value = true; difficulty.value = 16 }
        val store = factory(theme = theme, tor = tor, pow = pow).create()

        assertEquals(ThemeMode.DARK, store.state.theme)
        assertTrue(store.state.torEnabled)
        assertTrue(store.state.powEnabled)
        assertEquals(16, store.state.powDifficulty)
        assertEquals(listOf(8, 16), store.state.powLevels.map { it.difficulty })
    }

    @Test
    fun setters_route_to_their_repositories() = runTest {
        val theme = FakeThemeRepository()
        val tor = FakeTorRepository()
        val pow = FakePowRepository()
        val store = factory(theme = theme, tor = tor, pow = pow).create()

        store.accept(SettingsStore.Intent.ThemeSelected(ThemeMode.LIGHT))
        store.accept(SettingsStore.Intent.TorToggled(true))
        store.accept(SettingsStore.Intent.PowToggled(true))
        store.accept(SettingsStore.Intent.PowDifficultySelected(16))

        assertEquals(ThemeMode.LIGHT, theme.mode.value)
        assertTrue(tor.enabled.value)
        assertTrue(pow.enabled.value)
        assertEquals(16, pow.difficulty.value)
        assertEquals(ThemeMode.LIGHT, store.state.theme)
    }

    @Test
    fun notification_permission_status_loads() = runTest {
        val notifPermission = FakeNotificationPermissionRepository().apply { granted.value = true }
        val store = factory(notifPermission = notifPermission).create()

        assertEquals(NotifPermissionStatus.GRANTED, store.state.notifPermission)
    }

    @Test
    fun enable_notifications_requests_permission() = runTest {
        val notifPermission = FakeNotificationPermissionRepository()
        val store = factory(notifPermission = notifPermission).create()

        store.accept(SettingsStore.Intent.EnableNotificationsClicked)

        assertTrue(notifPermission.requested)
    }

    @Test
    fun global_mute_loads_and_toggles() = runTest {
        val notifSettings = FakeNotificationSettingsRepository().apply { globalMute.value = true }
        val store = factory(notifSettings = notifSettings).create()

        assertTrue(store.state.globalMuteEnabled)

        store.accept(SettingsStore.Intent.GlobalMuteToggled(false))

        assertEquals(false, notifSettings.globalMute.value)
        assertEquals(false, store.state.globalMuteEnabled)
    }

    @Test
    fun show_my_qr_builds_and_loads_into_state() = runTest {
        val verification = FakeVerificationRepository()
        val store = factory(verification = verification).create()

        store.accept(SettingsStore.Intent.ShowMyQrClicked)

        assertTrue(verification.built)
        assertEquals("bitchat://verify?x=1", store.state.myQr)
    }

    @Test
    fun mesh_background_loads_and_toggles() = runTest {
        val mesh = FakeMeshSettingsRepository().apply { autoStart.value = false; background.value = true }
        val store = factory(mesh = mesh).create()

        assertEquals(false, store.state.autoStartEnabled)
        assertEquals(true, store.state.backgroundEnabled)

        store.accept(SettingsStore.Intent.AutoStartToggled(true))
        store.accept(SettingsStore.Intent.BackgroundToggled(false))

        assertTrue(mesh.autoStart.value)
        assertEquals(false, mesh.background.value)
        assertEquals(false, store.state.backgroundEnabled)
    }
}
