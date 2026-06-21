package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.data.channel.ChannelCipher
import com.app.domain.repository.JoinResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelRepositoryImplTest {

    private class FakeSettingsStore : SettingsStore {
        private val data = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun getString(key: String, defaultValue: String): String = data.value[key] ?: defaultValue
        override fun getStringOrNull(key: String): String? = data.value[key]
        override fun putString(key: String, value: String) { data.value = data.value + (key to value) }
        override fun getStringFlow(key: String, defaultValue: String): Flow<String> = data.map { it[key] ?: defaultValue }
        override fun getStringOrNullFlow(key: String): Flow<String?> = data.map { it[key] }
        override fun getBooleanFlow(key: String, defaultValue: Boolean): Flow<Boolean> = flowOf(defaultValue)
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

    /** Commitment depends only on the password, like the real PBKDF2 key (salt = channel name). */
    private class FakeChannelCipher : ChannelCipher {
        private val passwords = mutableMapOf<String, String>()
        override fun setPassword(password: String, channel: String) { passwords[channel] = password }
        override fun keyCommitment(channel: String): String? = passwords[channel]?.let { "commit:$it" }
        override fun removePassword(channel: String) { passwords.remove(channel) }
    }

    private fun repo(
        settings: SettingsStore = FakeSettingsStore(),
        cipher: ChannelCipher = FakeChannelCipher(),
    ) = ChannelRepositoryImpl(settings, cipher)

    @Test
    fun unprotected_channel_joins_without_a_password() = runTest {
        val repo = repo()
        assertEquals(JoinResult.Joined, repo.join("general", password = null))
        assertTrue("#general" in repo.observeJoinedChannels().first())
    }

    @Test
    fun protected_channel_requires_a_password() = runTest {
        val repo = repo()
        repo.setPassword("secret-room", "the-password")

        // No password -> prompt
        assertEquals(JoinResult.NeedsPassword, repo.join("secret-room", password = null))
    }

    @Test
    fun wrong_password_is_rejected_on_join() = runTest {
        val cipher = FakeChannelCipher()
        val settings = FakeSettingsStore()
        // Creator sets the password (persists the commitment).
        repo(settings, cipher).setPassword("vault", "correct-horse")

        // A different device/session with the same persisted commitment tries a wrong password.
        val result = repo(settings, cipher).join("vault", password = "wrong-password")

        assertTrue(result is JoinResult.Failed)
    }

    @Test
    fun correct_password_joins_a_protected_channel() = runTest {
        val cipher = FakeChannelCipher()
        val settings = FakeSettingsStore()
        repo(settings, cipher).setPassword("vault", "correct-horse")

        val result = repo(settings, cipher).join("vault", password = "correct-horse")

        assertEquals(JoinResult.Joined, result)
    }
}
