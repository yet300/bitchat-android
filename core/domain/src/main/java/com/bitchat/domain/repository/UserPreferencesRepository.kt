package com.bitchat.domain.repository

interface UserPreferencesRepository {
    suspend fun getNickname(): String
    suspend fun saveNickname(nickname: String): Result<Unit>
    suspend fun getFavoritePeers(): Set<String>
    suspend fun addFavoritePeer(peerId: String): Result<Unit>
    suspend fun removeFavoritePeer(peerId: String): Result<Unit>
    suspend fun getBlockedUsers(): Set<String>
    suspend fun blockUser(peerId: String): Result<Unit>
    suspend fun unblockUser(peerId: String): Result<Unit>
    suspend fun getLastGeohashChannel(): String?
    suspend fun saveLastGeohashChannel(channelData: String): Result<Unit>
}