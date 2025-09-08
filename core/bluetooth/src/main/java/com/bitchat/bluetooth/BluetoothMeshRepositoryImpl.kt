package com.bitchat.bluetooth

import com.bitchat.domain.model.BitchatMessage
import com.bitchat.domain.model.PeerInfo
import com.bitchat.domain.repository.BluetoothMeshRepository
import kotlinx.coroutines.flow.Flow

class BluetoothMeshRepositoryImpl : BluetoothMeshRepository {
    override suspend fun startMesh(): Result<Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun stopMesh(): Result<Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun connectToPeer(peerId: String): Result<Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun disconnectFromPeer(peerId: String): Result<Unit> {
        TODO("Not yet implemented")
    }

    override fun observeConnectedPeers(): Flow<List<String>> {
        TODO("Not yet implemented")
    }

    override fun observePeerInfo(): Flow<Map<String, PeerInfo>> {
        TODO("Not yet implemented")
    }

    override suspend fun sendMessage(message: BitchatMessage): Result<Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun broadcastMessage(message: BitchatMessage): Result<Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun initiateNoiseHandshake(peerId: String): Result<Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun hasEstablishedSession(peerId: String): Boolean {
        TODO("Not yet implemented")
    }
}