package com.bitchat.domain.repository

import com.bitchat.domain.geohash.ChannelID
import com.bitchat.domain.geohash.GeohashChannel
import com.bitchat.domain.geohash.GeohashChannelLevel
import kotlinx.coroutines.flow.Flow

interface GeohashRepository {
    suspend fun getCurrentLocation(): Result<ChannelID.Location>
    suspend fun getAvailableChannels(): Flow<List<GeohashChannel>>
    suspend fun selectChannel(channel: GeohashChannel): Result<Unit>
    suspend fun getSelectedChannel(): Flow<ChannelID>
    suspend fun isLocationPermissionGranted(): Boolean
    suspend fun requestLocationPermission(): Result<Unit>
    suspend fun getLocationNames(): Flow<Map<GeohashChannelLevel, String>>
    suspend fun isTeleported(): Flow<Boolean>
}