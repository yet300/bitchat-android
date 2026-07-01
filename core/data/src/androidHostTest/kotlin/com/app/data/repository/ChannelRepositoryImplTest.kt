package com.app.data.repository

import com.app.data.channel.ChannelCipher
import com.app.domain.repository.JoinResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelRepositoryImplTest {

    /** Commitment depends only on the password, like the real PBKDF2 key (salt = channel name). */
    private class FakeChannelCipher : ChannelCipher {
        private val passwords = mutableMapOf<String, String>()
        override fun setPassword(password: String, channel: String) { passwords[channel] = password }
        override fun keyCommitment(channel: String): String? = passwords[channel]?.let { "commit:$it" }
        override fun removePassword(channel: String) { passwords.remove(channel) }
    }

    private fun repo(
        db: InMemoryDatabase = InMemoryDatabase(),
        cipher: ChannelCipher = FakeChannelCipher(),
    ) = ChannelRepositoryImpl(db.channelDao, cipher)

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
        val db = InMemoryDatabase()
        val cipher = FakeChannelCipher()
        // Creator sets the password (persists the commitment in the DB).
        repo(db, cipher).setPassword("vault", "correct-horse")

        // A different session over the same DB tries a wrong password.
        val result = repo(db, cipher).join("vault", password = "wrong-password")

        assertTrue(result is JoinResult.Failed)
    }

    @Test
    fun correct_password_joins_a_protected_channel() = runTest {
        val db = InMemoryDatabase()
        val cipher = FakeChannelCipher()
        repo(db, cipher).setPassword("vault", "correct-horse")

        val result = repo(db, cipher).join("vault", password = "correct-horse")

        assertEquals(JoinResult.Joined, result)
        assertTrue("#vault" in repo(db, cipher).observeJoinedChannels().first())
    }
}
