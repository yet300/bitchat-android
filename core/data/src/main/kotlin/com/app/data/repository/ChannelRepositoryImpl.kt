package com.app.data.repository

import com.app.data.channel.ChannelCipher
import com.app.database.Channel as ChannelEntity
import com.app.database.dao.ChannelDao
import com.app.domain.model.Channel
import com.app.domain.model.RetentionPolicy
import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.JoinResult
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Channel membership / protection / key-commitment / retention in the encrypted DB ([ChannelDao]).
 * The derived channel key is held in memory by [ChannelCipher] (re-established on join from the
 * password); only the protected flag and the key commitment survive a restart — in the encrypted DB.
 */
@SingleIn(AppScope::class)
@Inject
internal class ChannelRepositoryImpl(
    private val channelDao: ChannelDao,
    private val cipher: ChannelCipher,
) : ChannelRepository {

    override fun observeJoinedChannels(): Flow<Set<String>> =
        channelDao.observeJoined().map { it.toSet() }

    override fun observeChannels(): Flow<List<Channel>> =
        channelDao.observeAll().map { rows ->
            rows.filter { it.is_joined == 1L }
                .sortedBy { it.tag }
                .map { Channel(tag = it.tag, isJoined = true, isPasswordProtected = it.is_password_protected == 1L) }
        }

    override suspend fun join(tag: String, password: String?): JoinResult {
        val channel = Channel.tag(tag)
        val row = channelDao.byTag(channel)
        if (row?.is_password_protected == 1L) {
            if (password == null) return JoinResult.NeedsPassword
            // Derive the candidate key and verify it against the persisted commitment.
            cipher.setPassword(password, channel)
            val candidate = cipher.keyCommitment(channel)
            val expected = row.key_commitment
            if (expected != null && candidate != expected) {
                cipher.removePassword(channel)
                return JoinResult.Failed("Incorrect password")
            }
            // Trust-on-first-use: first device to supply a password defines the commitment.
            val commitment = expected ?: candidate
            channelDao.upsert(rowOf(channel, joined = true, protected = true, commitment = commitment, retention = row.retention_policy))
            return JoinResult.Joined
        }
        channelDao.upsert(rowOf(channel, joined = true, protected = row?.is_password_protected == 1L, commitment = row?.key_commitment, retention = row?.retention_policy))
        return JoinResult.Joined
    }

    override suspend fun leave(tag: String) {
        val channel = Channel.tag(tag)
        val row = channelDao.byTag(channel)
        // Unjoin and drop protection; the key commitment is retained for a later rejoin (parity).
        channelDao.upsert(rowOf(channel, joined = false, protected = false, commitment = row?.key_commitment, retention = row?.retention_policy))
        cipher.removePassword(channel)
    }

    override suspend fun setPassword(tag: String, password: String) {
        val channel = Channel.tag(tag)
        cipher.setPassword(password, channel)
        val row = channelDao.byTag(channel)
        channelDao.upsert(
            rowOf(channel, joined = row?.is_joined == 1L, protected = true, commitment = cipher.keyCommitment(channel), retention = row?.retention_policy),
        )
    }

    override suspend fun isCreator(tag: String): Boolean {
        // Needs my current peer id from the identity layer (a later step); unresolved from state alone.
        return false
    }

    override fun observeRetention(tag: String): Flow<RetentionPolicy> {
        val channel = Channel.tag(tag)
        return channelDao.observeAll().map { rows ->
            val stored = rows.firstOrNull { it.tag == channel }?.retention_policy
            stored?.let { runCatching { RetentionPolicy.valueOf(it) }.getOrNull() } ?: RetentionPolicy.KEEP_ALL
        }
    }

    override suspend fun setRetention(tag: String, policy: RetentionPolicy) {
        val channel = Channel.tag(tag)
        val row = channelDao.byTag(channel)
        channelDao.upsert(
            rowOf(channel, joined = row?.is_joined == 1L, protected = row?.is_password_protected == 1L, commitment = row?.key_commitment, retention = policy.name),
        )
    }

    private fun rowOf(tag: String, joined: Boolean, protected: Boolean, commitment: String?, retention: String?): ChannelEntity =
        ChannelEntity(
            tag = tag,
            is_joined = if (joined) 1L else 0L,
            is_password_protected = if (protected) 1L else 0L,
            key_commitment = commitment,
            retention_policy = retention ?: RetentionPolicy.KEEP_ALL.name,
        )
}
