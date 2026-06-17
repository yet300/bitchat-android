package com.yet.bitmessage.android.data

import android.util.Log
import com.app.common.serialization.JsonConfig
import com.russhwolf.settings.Settings
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.random.Random

/**
 * Handles data persistence operations for the chat system.
 *
 * String sets are stored as JSON strings since multiplatform-settings has no
 * native set type; the [JsonConfig] codec keeps that encoding consistent with
 * the rest of the app.
 */
class DataManager(private val settings: Settings) {

    companion object {
        private const val TAG = "DataManager"
    }

    // Channel-related maps that need to persist state
    private val _channelCreators = mutableMapOf<String, String>()
    private val _favoritePeers = mutableSetOf<String>()
    private val _blockedUsers = mutableSetOf<String>()
    private val _channelMembers = mutableMapOf<String, MutableSet<String>>()

    val channelCreators: Map<String, String> get() = _channelCreators
    val favoritePeers: Set<String> get() = _favoritePeers
    val blockedUsers: Set<String> get() = _blockedUsers
    val channelMembers: Map<String, MutableSet<String>> get() = _channelMembers

    private fun getStringSet(key: String): Set<String> {
        val json = settings.getStringOrNull(key) ?: return emptySet()
        return runCatching {
            JsonConfig.json.decodeFromString(SetSerializer(String.serializer()), json)
        }.getOrDefault(emptySet())
    }

    private fun putStringSet(key: String, values: Set<String>) {
        settings.putString(key, JsonConfig.json.encodeToString(SetSerializer(String.serializer()), values))
    }

    // MARK: - Nickname Management

    fun loadNickname(): String {
        val savedNickname = settings.getStringOrNull("nickname")
        return if (savedNickname != null) {
            savedNickname
        } else {
            val randomNickname = "anon${Random.nextInt(1000, 9999)}"
            saveNickname(randomNickname)
            randomNickname
        }
    }

    fun saveNickname(nickname: String) {
        settings.putString("nickname", nickname)
    }

    // MARK: - Geohash Channel Persistence

    fun loadLastGeohashChannel(): String? {
        return settings.getStringOrNull("last_geohash_channel")
    }

    fun saveLastGeohashChannel(channelData: String) {
        settings.putString("last_geohash_channel", channelData)
        Log.d(TAG, "Saved last geohash channel: $channelData")
    }

    fun clearLastGeohashChannel() {
        settings.remove("last_geohash_channel")
        Log.d(TAG, "Cleared last geohash channel")
    }

    // MARK: - Location Services State

    fun saveLocationServicesEnabled(enabled: Boolean) {
        settings.putBoolean("location_services_enabled", enabled)
        Log.d(TAG, "Saved location services enabled state: $enabled")
    }

    fun isLocationServicesEnabled(): Boolean {
        return settings.getBoolean("location_services_enabled", true) // Default to enabled
    }

    // MARK: - Channel Data Management

    fun loadChannelData(): Pair<Set<String>, Set<String>> {
        // Load joined channels
        val savedChannels = getStringSet("joined_channels")

        // Load password protected channels
        val savedProtectedChannels = getStringSet("password_protected_channels")

        // Load channel creators
        val creatorsJson = settings.getString("channel_creators", "{}")
        try {
            val creatorsMap = JsonConfig.json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                creatorsJson
            )
            _channelCreators.putAll(creatorsMap)
        } catch (e: Exception) {
            // Ignore parsing errors
        }

        // Initialize channel members for loaded channels
        savedChannels.forEach { channel ->
            if (!_channelMembers.containsKey(channel)) {
                _channelMembers[channel] = mutableSetOf()
            }
        }

        return Pair(savedChannels, savedProtectedChannels)
    }

    fun saveChannelData(joinedChannels: Set<String>, passwordProtectedChannels: Set<String>) {
        putStringSet("joined_channels", joinedChannels)
        putStringSet("password_protected_channels", passwordProtectedChannels)
        settings.putString(
            "channel_creators",
            JsonConfig.json.encodeToString(
                MapSerializer(String.serializer(), String.serializer()),
                _channelCreators
            )
        )
    }

    fun addChannelCreator(channel: String, creatorID: String) {
        _channelCreators[channel] = creatorID
    }

    fun removeChannelCreator(channel: String) {
        _channelCreators.remove(channel)
    }

    fun isChannelCreator(channel: String, peerID: String): Boolean {
        return _channelCreators[channel] == peerID
    }

    // MARK: - Channel Members Management

    fun addChannelMember(channel: String, peerID: String) {
        if (!_channelMembers.containsKey(channel)) {
            _channelMembers[channel] = mutableSetOf()
        }
        _channelMembers[channel]?.add(peerID)
    }

    fun removeChannelMember(channel: String, peerID: String) {
        _channelMembers[channel]?.remove(peerID)
    }

    fun removeChannelMembers(channel: String) {
        _channelMembers.remove(channel)
    }

    fun cleanupDisconnectedMembers(channel: String, connectedPeers: List<String>, myPeerID: String) {
        _channelMembers[channel]?.removeAll { memberID ->
            memberID != myPeerID && !connectedPeers.contains(memberID)
        }
    }

    fun cleanupAllDisconnectedMembers(connectedPeers: List<String>, myPeerID: String) {
        _channelMembers.values.forEach { members ->
            members.removeAll { memberID ->
                memberID != myPeerID && !connectedPeers.contains(memberID)
            }
        }
    }

    // MARK: - Favorites Management

    fun loadFavorites() {
        val savedFavorites = getStringSet("favorites")
        _favoritePeers.addAll(savedFavorites)
        Log.d(TAG, "Loaded ${savedFavorites.size} favorite users from storage: $savedFavorites")
    }

    fun saveFavorites() {
        putStringSet("favorites", _favoritePeers)
        Log.d(TAG, "Saved ${_favoritePeers.size} favorite users to storage: $_favoritePeers")
    }

    fun addFavorite(fingerprint: String) {
        val wasAdded = _favoritePeers.add(fingerprint)
        Log.d(TAG, "addFavorite: fingerprint=$fingerprint, wasAdded=$wasAdded")
        saveFavorites()
        logAllFavorites()
    }

    fun removeFavorite(fingerprint: String) {
        val wasRemoved = _favoritePeers.remove(fingerprint)
        Log.d(TAG, "removeFavorite: fingerprint=$fingerprint, wasRemoved=$wasRemoved")
        saveFavorites()
        logAllFavorites()
    }

    fun isFavorite(fingerprint: String): Boolean {
        val result = _favoritePeers.contains(fingerprint)
        Log.d(TAG, "isFavorite check: fingerprint=$fingerprint, result=$result")
        return result
    }

    fun logAllFavorites() {
        Log.i(TAG, "=== ALL FAVORITE USERS ===")
        Log.i(TAG, "Total favorites: ${_favoritePeers.size}")
        _favoritePeers.forEach { fingerprint ->
            Log.i(TAG, "Favorite fingerprint: $fingerprint")
        }
        Log.i(TAG, "========================")
    }

    // MARK: - Blocked Users Management

    fun loadBlockedUsers() {
        val savedBlockedUsers = getStringSet("blocked_users")
        _blockedUsers.addAll(savedBlockedUsers)
    }

    fun saveBlockedUsers() {
        putStringSet("blocked_users", _blockedUsers)
    }

    fun addBlockedUser(fingerprint: String) {
        _blockedUsers.add(fingerprint)
        saveBlockedUsers()
    }

    fun removeBlockedUser(fingerprint: String) {
        _blockedUsers.remove(fingerprint)
        saveBlockedUsers()
    }

    fun isUserBlocked(fingerprint: String): Boolean {
        return _blockedUsers.contains(fingerprint)
    }

    // MARK: - Geohash Blocked Users Management

    private val _geohashBlockedUsers = mutableSetOf<String>() // Set of nostr pubkey hex
    val geohashBlockedUsers: Set<String> get() = _geohashBlockedUsers.toSet()

    fun loadGeohashBlockedUsers() {
        val savedGeohashBlockedUsers = getStringSet("geohash_blocked_users")
        _geohashBlockedUsers.addAll(savedGeohashBlockedUsers)
    }

    fun saveGeohashBlockedUsers() {
        putStringSet("geohash_blocked_users", _geohashBlockedUsers)
    }

    fun addGeohashBlockedUser(pubkeyHex: String) {
        _geohashBlockedUsers.add(pubkeyHex)
        saveGeohashBlockedUsers()
    }

    fun removeGeohashBlockedUser(pubkeyHex: String) {
        _geohashBlockedUsers.remove(pubkeyHex)
        saveGeohashBlockedUsers()
    }

    fun isGeohashUserBlocked(pubkeyHex: String): Boolean {
        return _geohashBlockedUsers.contains(pubkeyHex)
    }

    // MARK: - Emergency Clear

    fun clearAllData() {
        _channelCreators.clear()
        _favoritePeers.clear()
        _blockedUsers.clear()
        _geohashBlockedUsers.clear()
        _channelMembers.clear()
        settings.clear()
    }
}
