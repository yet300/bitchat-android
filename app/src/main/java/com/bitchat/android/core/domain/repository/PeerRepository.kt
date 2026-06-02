package com.bitchat.android.core.domain.repository

import com.bitchat.android.core.domain.model.Peer
import com.bitchat.android.core.domain.model.PeerId
import kotlinx.coroutines.flow.Flow

/**
 * Доступ к состоянию пиров mesh-сети.
 */
interface PeerRepository {

    /** Поток пиров (подключённость, сессия, fingerprint, rssi и т.д.). */
    fun observePeers(): Flow<List<Peer>>

    /** Поток общего состояния связи (есть ли хоть один пир). */
    fun observeConnectionState(): Flow<Boolean>

    /** Текущий снимок пиров (для разовых резолвов). */
    suspend fun snapshot(): List<Peer>

    /** Найти пира по id. */
    suspend fun peer(id: PeerId): Peer?
}
