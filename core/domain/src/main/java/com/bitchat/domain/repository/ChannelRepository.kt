package com.bitchat.domain.repository

import com.bitchat.domain.model.BitchatMessage
import kotlinx.coroutines.flow.Flow

interface ChannelRepository {
    suspend fun joinChannel(channelName: String, password: String? = null): Result<Unit>
    suspend fun leaveChannel(channelName: String): Result<Unit>
    suspend fun createChannel(channelName: String, password: String? = null): Result<Unit>
    fun observeJoinedChannels(): Flow<Set<String>>
    fun observeChannelMessages(channelName: String): Flow<List<BitchatMessage>>
    suspend fun addChannelMember(channelName: String, peerId: String): Result<Unit>
    suspend fun removeChannelMember(channelName: String, peerId: String): Result<Unit>
    suspend fun isChannelPasswordProtected(channelName: String): Boolean
    suspend fun getChannelMembers(channelName: String): List<String>
}