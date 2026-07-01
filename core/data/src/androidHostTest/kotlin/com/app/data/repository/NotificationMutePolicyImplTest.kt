@file:OptIn(ExperimentalCoroutinesApi::class)

package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.PeerId
import com.app.transport.mesh.MeshService
import com.app.transport.mesh.PeerInfo
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class NotificationMutePolicyImplTest {

    private val noiseHex = "aa".repeat(32) // 64 hex → NOISE_STABLE
    private val noiseBytes = ByteArray(32) { 0xAA.toByte() }

    /** A mesh provider that maps the given mesh peerIDs to a peer carrying [noiseBytes]. */
    private fun meshFor(vararg knownPeerIds: String): Provider<MeshService> {
        val mesh = mock<MeshService> {
            knownPeerIds.forEach { id ->
                whenever(it.getPeerInfo(id)).thenReturn(
                    PeerInfo(
                        id = id,
                        nickname = "bob",
                        isConnected = true,
                        isDirectConnection = true,
                        noisePublicKey = noiseBytes,
                        signingPublicKey = null,
                        isVerifiedNickname = false,
                        lastSeen = 0L,
                    ),
                )
            }
        }
        return Provider { mesh }
    }

    private class FakeSettingsStore : SettingsStore {
        private val bools = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        override fun getBoolean(key: String, defaultValue: Boolean) = bools.value[key] ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { bools.value = bools.value + (key to value) }
        override fun getBooleanFlow(key: String, defaultValue: Boolean): Flow<Boolean> = bools.map { it[key] ?: defaultValue }
        override fun getString(key: String, defaultValue: String) = defaultValue
        override fun getStringOrNull(key: String): String? = null
        override fun putString(key: String, value: String) = Unit
        override fun getStringFlow(key: String, defaultValue: String): Flow<String> = MutableStateFlow(defaultValue)
        override fun getStringOrNullFlow(key: String): Flow<String?> = MutableStateFlow(null)
        override fun getInt(key: String, defaultValue: Int) = defaultValue
        override fun putInt(key: String, value: Int) = Unit
        override fun getLong(key: String, defaultValue: Long) = defaultValue
        override fun putLong(key: String, value: Long) = Unit
        override fun getDouble(key: String, defaultValue: Double) = defaultValue
        override fun putDouble(key: String, value: Double) = Unit
        override fun hasKey(key: String) = bools.value.containsKey(key)
        override fun remove(key: String) { bools.value = bools.value - key }
    }

    private fun prefs(db: InMemoryDatabase) = ConversationPrefsRepositoryImpl(db.conversationPrefDao)

    private fun policy(db: InMemoryDatabase, settings: SettingsStore, mesh: Provider<MeshService>) =
        NotificationMutePolicyImpl(settings, mesh, db.conversationPrefDao, CoroutineScope(UnconfinedTestDispatcher()))

    @Test
    fun honors_per_conversation_mute_written_by_the_prefs_repo() = runTest {
        val db = InMemoryDatabase()
        val settings = FakeSettingsStore()
        val prefs = prefs(db)
        val policy = policy(db, settings, meshFor())

        prefs.setMuted(ConversationId.Private(PeerId("peer1")), true)
        prefs.setMuted(ConversationId.Geohash(GeohashChannel(GeohashLevel.CITY, "u4pruyd")), true)

        assertTrue(policy.isPrivateMuted("peer1"))
        assertFalse(policy.isPrivateMuted("peer2"))
        // Geohash matches by hash regardless of the stored precision level.
        assertTrue(policy.isGeohashMuted("u4pruyd"))
        assertFalse(policy.isGeohashMuted("9q8yy"))
        assertFalse(policy.isAllMuted())
    }

    @Test
    fun global_mute_reads_the_shared_key() = runTest {
        val settings = FakeSettingsStore()
        NotificationSettingsRepositoryImpl(settings).setGlobalMuteEnabled(true)

        assertTrue(policy(InMemoryDatabase(), settings, meshFor()).isAllMuted())
    }

    @Test
    fun private_mute_stored_by_noise_key_suppresses_a_fresh_session_mesh_peer() = runTest {
        val db = InMemoryDatabase()
        val settings = FakeSettingsStore()
        // The conversation-list store persists mute under the stable Noise key (I12-2).
        prefs(db).setMuted(ConversationId.Private(PeerId(noiseHex)), true)

        // A NEW session: a different ephemeral mesh peerID that resolves to the same Noise key.
        val freshMeshId = "1234567890abcdef"
        val policy = policy(db, settings, meshFor(freshMeshId))

        assertTrue(policy.isPrivateMuted(freshMeshId))
        // An unrelated peer (no Noise mapping) is not suppressed.
        assertFalse(policy.isPrivateMuted("fedcba0987654321"))
    }
}
