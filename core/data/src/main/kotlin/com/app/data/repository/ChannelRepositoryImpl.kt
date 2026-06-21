package com.app.data.repository

import com.app.common.serialization.JsonConfig
import com.app.data.channel.ChannelCipher
import com.app.domain.model.Channel
import com.app.domain.model.RetentionPolicy
import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.JoinResult
import com.app.common.settings.SettingsStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Channel membership/password persistence over the shared settings store (same keys as the legacy
 * `ChannelManager`/`DataManager`).
 *
 * Channel password crypto is re-homed onto the surviving PBKDF2/AES-GCM primitive (iOS-compatible)
 * via the [ChannelCipher] port: [setPassword] derives and caches the channel key and
 * persists its commitment; [join] verifies a supplied password against that commitment. The derived
 * key is held in memory (re-established on join from the password); only the protected flag and the
 * key commitment survive a restart. Carrying the channel tag + ciphertext over the mesh broadcast
 * wire is out of scope here — the mesh public path transmits raw content (parity boundary).
 */
@SingleIn(AppScope::class)
@Inject
internal class ChannelRepositoryImpl(
    private val settings: SettingsStore,
    private val cipher: ChannelCipher,
) : ChannelRepository {

    override fun observeJoinedChannels(): Flow<Set<String>> =
        settings.getStringOrNullFlow(KEY_JOINED).map(::decodeSet)

    override fun observeChannels(): Flow<List<Channel>> =
        combine(
            settings.getStringOrNullFlow(KEY_JOINED).map(::decodeSet),
            settings.getStringOrNullFlow(KEY_PROTECTED).map(::decodeSet),
        ) { joined, protected ->
            joined.sorted().map { tag ->
                Channel(tag = tag, isJoined = true, isPasswordProtected = tag in protected)
            }
        }

    override suspend fun join(tag: String, password: String?): JoinResult {
        val channel = Channel.tag(tag)
        val isProtected = channel in loadSet(KEY_PROTECTED)
        if (isProtected) {
            if (password == null) return JoinResult.NeedsPassword
            // Derive the candidate key and verify it against the persisted commitment.
            cipher.setPassword(password, channel)
            val candidate = cipher.keyCommitment(channel)
            val expected = settings.getStringOrNull(commitmentKey(channel))
            if (expected != null && candidate != expected) {
                // Wrong password: drop the derived key so we never encrypt/decrypt with it.
                cipher.removePassword(channel)
                return JoinResult.Failed("Incorrect password")
            }
            // Trust-on-first-use: first device to supply a password defines the commitment.
            if (expected == null && candidate != null) settings.putString(commitmentKey(channel), candidate)
        }
        saveSet(KEY_JOINED, loadSet(KEY_JOINED) + channel)
        return JoinResult.Joined
    }

    override suspend fun leave(tag: String) {
        val channel = Channel.tag(tag)
        saveSet(KEY_JOINED, loadSet(KEY_JOINED) - channel)
        saveSet(KEY_PROTECTED, loadSet(KEY_PROTECTED) - channel)
        cipher.removePassword(channel)
    }

    override suspend fun setPassword(tag: String, password: String) {
        val channel = Channel.tag(tag)
        // Derive + cache the channel key now, mark the channel protected, and persist the key
        // commitment so a later join (here or on another device) can verify the password.
        cipher.setPassword(password, channel)
        saveSet(KEY_PROTECTED, loadSet(KEY_PROTECTED) + channel)
        cipher.keyCommitment(channel)?.let { settings.putString(commitmentKey(channel), it) }
    }

    override suspend fun isCreator(tag: String): Boolean {
        // Creators are stored by the creating peer's id; answering "am I the creator" needs my current
        // peer id from the identity layer (IdentityRepository, a later Phase B step), so it cannot be
        // resolved from persisted state alone yet.
        return false
    }

    override fun observeRetention(tag: String): Flow<RetentionPolicy> =
        settings.getStringFlow(retentionKey(tag), RetentionPolicy.KEEP_ALL.name).map { stored ->
            runCatching { RetentionPolicy.valueOf(stored) }.getOrDefault(RetentionPolicy.KEEP_ALL)
        }

    override suspend fun setRetention(tag: String, policy: RetentionPolicy) {
        settings.putString(retentionKey(tag), policy.name)
    }

    private fun retentionKey(tag: String): String = "$KEY_RETENTION_PREFIX${Channel.tag(tag)}"

    private fun commitmentKey(channelTag: String): String = "$KEY_COMMITMENT_PREFIX$channelTag"

    private fun loadSet(key: String): Set<String> = decodeSet(settings.getStringOrNull(key))

    private fun saveSet(key: String, values: Set<String>) {
        settings.putString(key, JsonConfig.json.encodeToString(SET_SERIALIZER, values))
    }

    private fun decodeSet(json: String?): Set<String> {
        if (json == null) return emptySet()
        return runCatching { JsonConfig.json.decodeFromString(SET_SERIALIZER, json) }.getOrDefault(emptySet())
    }

    private companion object {
        const val KEY_JOINED = "joined_channels"
        const val KEY_PROTECTED = "password_protected_channels"
        const val KEY_RETENTION_PREFIX = "channel_retention_"
        const val KEY_COMMITMENT_PREFIX = "channel_key_commitment_"
        val SET_SERIALIZER = SetSerializer(String.serializer())
    }
}
