package com.bitchat.domain.repository

import com.bitchat.domain.model.BitchatMessage
import com.bitchat.domain.model.NostrIdentity
import com.bitchat.domain.model.ReadReceipt
import kotlinx.coroutines.flow.Flow

interface NostrRepository {
    suspend fun initialize(): Result<Unit>
    suspend fun connectToRelays(): Result<Unit>
    suspend fun disconnectFromRelays(): Result<Unit>
    suspend fun sendPrivateMessage(content: String, toPeerId: String, recipientNickname: String, messageId: String): Result<Unit>
    suspend fun sendReadReceipt(receipt: ReadReceipt, toPeerId: String): Result<Unit>
    fun observeNostrMessages(): Flow<List<BitchatMessage>>
    suspend fun getCurrentIdentity(): NostrIdentity?
    suspend fun isConnectedToRelays(): Boolean
    suspend fun subscribeToGiftWraps(): Result<Unit>
}