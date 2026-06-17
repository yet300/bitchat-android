package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.PeerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMutePolicyImplTest {

    private class FakeSettingsStore : SettingsStore {
        private val data = MutableStateFlow<Map<String, String>>(emptyMap())
        private val bools = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        override fun getString(key: String, defaultValue: String): String = data.value[key] ?: defaultValue
        override fun getStringOrNull(key: String): String? = data.value[key]
        override fun putString(key: String, value: String) { data.value = data.value + (key to value) }
        override fun getStringFlow(key: String, defaultValue: String): Flow<String> = data.map { it[key] ?: defaultValue }
        override fun getStringOrNullFlow(key: String): Flow<String?> = data.map { it[key] }
        override fun getBoolean(key: String, defaultValue: Boolean) = bools.value[key] ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { bools.value = bools.value + (key to value) }
        override fun getBooleanFlow(key: String, defaultValue: Boolean): Flow<Boolean> = bools.map { it[key] ?: defaultValue }
        override fun getInt(key: String, defaultValue: Int) = defaultValue
        override fun putInt(key: String, value: Int) = Unit
        override fun getLong(key: String, defaultValue: Long) = defaultValue
        override fun putLong(key: String, value: Long) = Unit
        override fun getDouble(key: String, defaultValue: Double) = defaultValue
        override fun putDouble(key: String, value: Double) = Unit
        override fun hasKey(key: String) = data.value.containsKey(key)
        override fun remove(key: String) { data.value = data.value - key }
    }

    @Test
    fun honors_per_conversation_mute_written_by_the_prefs_repo() = runTest {
        val settings = FakeSettingsStore()
        val prefs = ConversationPrefsRepositoryImpl(settings)
        val policy = NotificationMutePolicyImpl(settings)

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

        assertTrue(NotificationMutePolicyImpl(settings).isAllMuted())
    }
}
