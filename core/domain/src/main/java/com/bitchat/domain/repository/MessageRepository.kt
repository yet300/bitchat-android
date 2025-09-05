package com.bitchat.domain.repository

import com.bitchat.domain.model.BitchatMessage
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    suspend fun sendMessage(message: BitchatMessage): Result<Unit>
    suspend fun sendPrivateMessage(message: BitchatMessage, recipientId: String): Result<Unit>
    suspend fun sendReadReceipt(messageId: String, recipientId: String): Result<Unit>
    fun observeMessages(): Flow<List<BitchatMessage>>
    fun observePrivateMessages(peerId: String): Flow<List<BitchatMessage>>
    suspend fun markMessageAsRead(messageId: String): Result<Unit>
    suspend fun markMessageAsDelivered(messageId: String): Result<Unit>
    suspend fun hasSeenMessage(messageId: String): Boolean
}