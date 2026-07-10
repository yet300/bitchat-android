@file:OptIn(kotlin.time.ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.crypto.EncryptionService
import com.app.data.AppStateStore
import com.app.domain.app.AppForegroundState
import com.app.domain.model.ConversationId
import com.app.data.FakeContactRepository
import com.app.data.nostr.GeohashPresencePublisher
import com.app.data.transportconfig.SettingsNostrConfigStore
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.transport.SeenMessageStore
import com.app.transport.nostr.GeohashAliasRegistry
import com.app.transport.nostr.GeohashConversationRegistry
import com.app.transport.nostr.NostrEvent
import com.app.transport.nostr.NostrIdentity
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.nostr.NostrKind
import com.app.transport.nostr.NostrRelayManager
import com.app.transport.nostr.PoWPreferenceManager
import com.app.transport.nostr.RelayDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GeohashRepositoryImplTest {

    private companion object {
        const val GEOHASH = "u4pru"
        val MY_HEX = "aa".repeat(32)
        val OTHER_HEX = "bb".repeat(32)
        val THIRD_HEX = "cc".repeat(32)
        val GEO = ConversationId.Geohash(GeohashChannel(GeohashLevel.CITY, GEOHASH))
    }

    /** In-memory SettingsStore backing the registries via the real SettingsNostrConfigStore. */
    private class FakeSettingsStore : SettingsStore {
        private val strings = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun getStringOrNull(key: String): String? = strings.value[key]
        override fun getString(key: String, defaultValue: String): String = strings.value[key] ?: defaultValue
        override fun putString(key: String, value: String) { strings.value = strings.value + (key to value) }
        override fun remove(key: String) { strings.value = strings.value - key }
        override fun hasKey(key: String) = strings.value.containsKey(key)
        override fun getStringFlow(key: String, defaultValue: String): Flow<String> =
            strings.map { it[key] ?: defaultValue }
        override fun getStringOrNullFlow(key: String): Flow<String?> = strings.map { it[key] }
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun getBooleanFlow(key: String, defaultValue: Boolean): Flow<Boolean> = MutableStateFlow(defaultValue)
        override fun getInt(key: String, defaultValue: Int) = defaultValue
        override fun putInt(key: String, value: Int) = Unit
        override fun getLong(key: String, defaultValue: Long) = defaultValue
        override fun putLong(key: String, value: Long) = Unit
        override fun getDouble(key: String, defaultValue: Double) = defaultValue
        override fun putDouble(key: String, value: Double) = Unit
    }

    private lateinit var appStateStore: AppStateStore
    private lateinit var relayManager: NostrRelayManager
    private lateinit var repo: GeohashRepositoryImpl

    @Before
    fun setUp() {
        val encryptionService = mock<EncryptionService>()
        whenever(encryptionService.getIdentityFingerprint()).thenReturn("ffffffffffffffff")
        val seen = mock<SeenMessageStore>()
        whenever(seen.hasRead(any())).thenReturn(false)
        appStateStore = AppStateStore(encryptionService, seen, { FakeContactRepository() })

        relayManager = mock()
        val relayDirectory = mock<RelayDirectory>()
        val pow = mock<PoWPreferenceManager>()
        whenever(pow.getCurrentSettings()).thenReturn(PoWPreferenceManager.PoWSettings(enabled = false, difficulty = 0))
        val bridge = mock<NostrIdentityBridge>()
        whenever(bridge.deriveIdentity(any())).thenReturn(NostrIdentity(MY_HEX, MY_HEX, "npub", 0L))
        val nostrConfigStore = SettingsNostrConfigStore(FakeSettingsStore())
        val foreground = object : AppForegroundState {
            override val isForeground = MutableStateFlow(true)
        }
        val scope = CoroutineScope(UnconfinedTestDispatcher())

        repo = GeohashRepositoryImpl(
            appStateStore = appStateStore,
            relayManager = relayManager,
            relayDirectory = relayDirectory,
            nostrIdentityBridge = bridge,
            powPreferenceManager = pow,
            aliasRegistry = GeohashAliasRegistry(nostrConfigStore),
            conversationRegistry = GeohashConversationRegistry(nostrConfigStore),
            geohashDao = InMemoryDatabase().geohashDao,
            appForegroundState = foreground,
            presencePublisher = GeohashPresencePublisher(relayManager, relayDirectory, bridge, foreground, scope),
            scope = scope,
        )
    }

    private fun nowSeconds() = (System.currentTimeMillis() / 1000).toInt()

    private fun chatEvent(
        id: String,
        pubkey: String,
        content: String = "hello",
        nick: String? = null,
        kind: Int = NostrKind.EPHEMERAL_EVENT,
    ): NostrEvent {
        val tags = buildList {
            add(listOf("g", GEOHASH))
            if (nick != null) add(listOf("n", nick))
        }
        return NostrEvent(id = id, pubkey = pubkey, createdAt = nowSeconds(), kind = kind, tags = tags, content = content)
    }

    private fun geoTimeline() = appStateStore.channelMessages.value[AppStateStore.geoChannelName(GEOHASH)].orEmpty()

    @Test
    fun incoming_chat_event_lands_in_the_geo_timeline_and_tracks_a_participant() = runTest {
        repo.select(GEO)
        repo.ingest(chatEvent("e1", OTHER_HEX, content = "hi there", nick = "bob"), GEOHASH)

        val timeline = geoTimeline()
        assertEquals(1, timeline.size)
        assertEquals("hi there", timeline.first().content)
        assertEquals(1, repo.observeParticipantCounts().first()[GEOHASH])
        assertEquals(OTHER_HEX, repo.observeParticipants().first().single().pubkeyHex)
    }

    @Test
    fun presence_event_tracks_a_participant_without_emitting_a_chat_message() = runTest {
        repo.select(GEO)
        repo.ingest(chatEvent("p1", OTHER_HEX, content = "", kind = NostrKind.GEOHASH_PRESENCE), GEOHASH)

        assertTrue(geoTimeline().isEmpty())
        assertEquals(1, repo.observeParticipantCounts().first()[GEOHASH])
    }

    @Test
    fun own_events_are_not_echoed_into_the_timeline() = runTest {
        repo.select(GEO)
        repo.ingest(chatEvent("mine", MY_HEX, content = "self"), GEOHASH)

        assertTrue(geoTimeline().isEmpty())
    }

    @Test
    fun blocked_participants_are_filtered_from_messages_and_counts() = runTest {
        repo.select(GEO)
        repo.setBlocked(OTHER_HEX, blocked = true)
        repo.ingest(chatEvent("e1", OTHER_HEX), GEOHASH)

        assertTrue(geoTimeline().isEmpty())
        assertTrue(repo.observeParticipants().first().isEmpty())
        assertEquals(0, repo.observeParticipantCounts().first()[GEOHASH] ?: 0)
    }

    @Test
    fun duplicate_event_ids_are_ingested_once() = runTest {
        repo.select(GEO)
        val event = chatEvent("dup", OTHER_HEX)
        repo.ingest(event, GEOHASH)
        repo.ingest(event, GEOHASH)

        assertEquals(1, geoTimeline().size)
    }

    @Test
    fun nickname_resolves_to_pubkey_and_opens_a_geohash_dm() = runTest {
        repo.select(GEO)
        repo.ingest(chatEvent("e1", OTHER_HEX, nick = "carol"), GEOHASH)

        assertEquals(OTHER_HEX, repo.pubkeyForNickname("carol"))
        assertNull(repo.pubkeyForNickname("nobody"))

        val dm = repo.startDirectMessage(OTHER_HEX)
        assertTrue(dm is ConversationId.Private)
        assertEquals("nostr_${OTHER_HEX.take(16)}", (dm as ConversationId.Private).peer.raw)
    }

    @Test
    fun events_for_a_different_geohash_are_ignored() = runTest {
        repo.select(GEO)
        val foreign = NostrEvent(
            id = "x", pubkey = THIRD_HEX, createdAt = nowSeconds(),
            kind = NostrKind.EPHEMERAL_EVENT, tags = listOf(listOf("g", "9q8yy")), content = "elsewhere",
        )
        repo.ingest(foreign, GEOHASH)

        assertTrue(geoTimeline().isEmpty())
    }
}
