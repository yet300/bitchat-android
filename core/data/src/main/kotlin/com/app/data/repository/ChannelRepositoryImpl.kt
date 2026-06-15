package com.app.data.repository

import com.app.common.serialization.JsonConfig
import com.app.domain.model.Channel
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
 * `ChannelManager`/`DataManager`). Channel crypto (PBKDF2 key derivation, AES-GCM message
 * encryption) is part of the messaging layer and currently disabled in the app, so only membership
 * and the password-protected flag are persisted here.
 */
@SingleIn(AppScope::class)
@Inject
internal class ChannelRepositoryImpl(
    private val settings: SettingsStore,
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
        if (channel in loadSet(KEY_PROTECTED) && password == null) {
            return JoinResult.NeedsPassword
        }
        // Password verification is currently a no-op in the app, so a provided password is accepted.
        saveSet(KEY_JOINED, loadSet(KEY_JOINED) + channel)
        return JoinResult.Joined
    }

    override suspend fun leave(tag: String) {
        val channel = Channel.tag(tag)
        saveSet(KEY_JOINED, loadSet(KEY_JOINED) - channel)
        saveSet(KEY_PROTECTED, loadSet(KEY_PROTECTED) - channel)
    }

    override suspend fun setPassword(tag: String, password: String) {
        // Only the protected flag survives a restart today (the derived key lives in memory and is
        // re-established on next join); key derivation/encryption return with the messaging layer.
        saveSet(KEY_PROTECTED, loadSet(KEY_PROTECTED) + Channel.tag(tag))
    }

    override suspend fun isCreator(tag: String): Boolean {
        // Creators are stored by the creating peer's id; answering "am I the creator" needs my current
        // peer id from the identity layer (IdentityRepository, a later Phase B step), so it cannot be
        // resolved from persisted state alone yet.
        return false
    }

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
        val SET_SERIALIZER = SetSerializer(String.serializer())
    }
}
