package com.bitchat.domain.repository

import com.bitchat.domain.model.BitchatMessage
import com.bitchat.domain.model.PeerInfo
import kotlinx.coroutines.flow.Flow

interface BluetoothMeshRepository {
    suspend fun startMesh(): Result<Unit>
    suspend fun stopMesh(): Result<Unit>
    suspend fun connectToPeer(peerId: String): Result<Unit>
    suspend fun disconnectFromPeer(peerId: String): Result<Unit>
    fun observeConnectedPeers(): Flow<List<String>>
    fun observePeerInfo(): Flow<Map<String, PeerInfo>>
    suspend fun sendMessage(message: BitchatMessage): Result<Unit>
    suspend fun broadcastMessage(message: BitchatMessage): Result<Unit>
    suspend fun initiateNoiseHandshake(peerId: String): Result<Unit>
    suspend fun hasEstablishedSession(peerId: String): Boolean
}