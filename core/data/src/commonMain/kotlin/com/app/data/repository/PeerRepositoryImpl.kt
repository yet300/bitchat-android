@file:OptIn(ExperimentalTime::class)

package com.app.data.repository

import com.app.common.encoding.hexEncodedString
import com.app.crypto.hash.Sha256
import com.app.data.AppStateStore
import com.app.domain.model.Fingerprint
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.SessionState
import com.app.domain.repository.PeerRepository
import com.app.transport.mesh.MeshService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Mesh peer state. The connected-peer-id stream is [appStateStore.peers] (the mesh sink calls
 * `setPeers`); the rich per-peer info comes from the injected [MeshService].
 */
@SingleIn(AppScope::class)
@Inject
internal class PeerRepositoryImpl(
    private val mesh: MeshService,
    private val appStateStore: AppStateStore,
) : PeerRepository {

    override fun observePeers(): Flow<List<Peer>> =
        appStateStore.peers.map { ids -> ids.mapNotNull(::toPeer) }

    override fun observeConnectionState(): Flow<Boolean> =
        appStateStore.peers.map { it.isNotEmpty() }

    override suspend fun snapshot(): List<Peer> =
        appStateStore.peers.value.mapNotNull(::toPeer)

    override suspend fun peer(id: PeerId): Peer? = toPeer(id.raw)

    private fun toPeer(peerID: String): Peer? {
        val info = mesh.getPeerInfo(peerID) ?: return null
        val noise = info.noisePublicKey
        return Peer(
            id = PeerId(info.id),
            nickname = info.nickname,
            isConnected = info.isConnected,
            isDirect = info.isDirectConnection,
            session = if (mesh.hasEstablishedSession(peerID)) SessionState.ESTABLISHED else SessionState.NONE,
            rssi = null, // RSSI is tracked separately by the mesh and not surfaced on PeerInfo
            fingerprint = noise?.let { Fingerprint(it.sha256Hex()) },
            noiseKeyHex = noise?.hexEncodedString(),
            isVerified = info.isVerifiedNickname,
            lastSeen = Instant.fromEpochMilliseconds(info.lastSeen),
        )
    }

    private fun ByteArray.sha256Hex(): String = Sha256.digest(this).hexEncodedString()
}
