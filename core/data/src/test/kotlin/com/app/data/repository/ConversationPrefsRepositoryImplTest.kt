package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.PeerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPrefsRepositoryImplTest {

    /** Minimal in-memory SettingsStore (only string ops are exercised here). */
    private class FakeSettingsStore : SettingsStore {
        private val data = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun getString(key: String, defaultValue: String): String = data.value[key] ?: defaultValue
        override fun getStringOrNull(key: String): String? = data.value[key]
        override fun putString(key: String, value: String) { data.value = data.value + (key to value) }
        override fun getStringFlow(key: String, defaultValue: String): Flow<String> =
            data.map { it[key] ?: defaultValue }
        override fun getStringOrNullFlow(key: String): Flow<String?> = data.map { it[key] }
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun getInt(key: String, defaultValue: Int) = defaultValue
        override fun putInt(key: String, value: Int) = Unit
        override fun getLong(key: String, defaultValue: Long) = defaultValue
        override fun putLong(key: String, value: Long) = Unit
        override fun getDouble(key: String, defaultValue: Double) = defaultValue
        override fun putDouble(key: String, value: Double) = Unit
        override fun hasKey(key: String) = data.value.containsKey(key)
        override fun remove(key: String) { data.value = data.value - key }
    }

    private fun repo() = ConversationPrefsRepositoryImpl(FakeSettingsStore())

    @Test
    fun pin_round_trips_across_all_conversation_kinds() = runTest {
        val repo = repo()
        val ids = listOf(
            ConversationId.PublicMesh,
            ConversationId.Channel("dev"),
            ConversationId.Private(PeerId("nostr_0123456789abcdef")),
            ConversationId.Geohash(GeohashChannel(GeohashLevel.CITY, "u4pruyd")),
        )
        ids.forEach { repo.setPinned(it, true) }

        assertEquals(ids.toSet(), repo.observePinned().first())
        assertTrue(repo.observeMuted().first().isEmpty())
    }

    @Test
    fun unpin_removes_only_the_target() = runTest {
        val repo = repo()
        val a = ConversationId.Channel("a")
        val b = ConversationId.Channel("b")
        repo.setPinned(a, true)
        repo.setPinned(b, true)

        repo.setPinned(a, false)

        assertEquals(setOf(b), repo.observePinned().first())
    }

    @Test
    fun pinned_and_muted_are_independent_sets() = runTest {
        val repo = repo()
        val id = ConversationId.Channel("dev")
        repo.setPinned(id, true)
        repo.setMuted(id, true)
        repo.setPinned(id, false)

        assertTrue(repo.observePinned().first().isEmpty())
        assertEquals(setOf(id), repo.observeMuted().first())
    }
}
