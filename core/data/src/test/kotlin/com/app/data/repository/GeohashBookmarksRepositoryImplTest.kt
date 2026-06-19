package com.app.data.repository

import com.app.common.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeohashBookmarksRepositoryImplTest {

    private class FakeSettingsStore : SettingsStore {
        private val strings = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun getString(key: String, defaultValue: String): String = strings.value[key] ?: defaultValue
        override fun getStringOrNull(key: String): String? = strings.value[key]
        override fun putString(key: String, value: String) { strings.value = strings.value + (key to value) }
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
        override fun hasKey(key: String) = strings.value.containsKey(key)
        override fun remove(key: String) { strings.value = strings.value - key }
    }

    private fun repo() = GeohashBookmarksRepositoryImpl(FakeSettingsStore())

    @Test
    fun toggle_adds_then_removes_and_normalises() = runTest {
        val repo = repo()
        assertFalse(repo.observeIsBookmarked("u4pru").first())

        repo.toggle("#U4PRU") // normalised: lowercase, strip '#'
        assertTrue(repo.observeIsBookmarked("u4pru").first())
        assertEquals(listOf("u4pru"), repo.observeBookmarks().first())

        repo.toggle("u4pru")
        assertFalse(repo.observeIsBookmarked("u4pru").first())
        assertTrue(repo.observeBookmarks().first().isEmpty())
    }

    @Test
    fun newest_bookmark_is_first() = runTest {
        val repo = repo()
        repo.toggle("9q8")
        repo.toggle("bcd")
        assertEquals(listOf("bcd", "9q8"), repo.observeBookmarks().first())
    }
}
