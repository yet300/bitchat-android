package com.app.data.repository

import com.app.common.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingRepositoryImplTest {

    /** In-memory SettingsStore backing boolean ops (the only ones this repo exercises). */
    private class FakeSettingsStore : SettingsStore {
        private val bools = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = bools.value[key] ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { bools.value = bools.value + (key to value) }
        override fun getBooleanFlow(key: String, defaultValue: Boolean): Flow<Boolean> =
            bools.map { it[key] ?: defaultValue }
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

    private fun repo() = OnboardingRepositoryImpl(FakeSettingsStore())

    @Test
    fun fresh_install_is_not_completed() = runTest {
        val repo = repo()
        assertFalse(repo.isCompleted())
        assertFalse(repo.observeCompleted().first())
    }

    @Test
    fun set_completed_flips_the_flag_for_sync_and_flow_reads() = runTest {
        val repo = repo()
        repo.setCompleted()
        assertTrue(repo.isCompleted())
        assertTrue(repo.observeCompleted().first())
    }

    @Test
    fun completion_persists_in_the_backing_store() = runTest {
        val store = FakeSettingsStore()
        OnboardingRepositoryImpl(store).setCompleted()
        // A second repo over the same store sees the persisted flag (survives a fresh construction).
        assertTrue(OnboardingRepositoryImpl(store).isCompleted())
    }
}
