package com.bitchat.domain.repository

import com.bitchat.domain.model.BitchatMessage

interface NotificationRepository {
    suspend fun showMessageNotification(message: BitchatMessage): Result<Unit>
    suspend fun showActivePeersNotification(peers: List<String>): Result<Unit>
    suspend fun clearNotifications(): Result<Unit>
    suspend fun setAppBackgroundState(inBackground: Boolean): Result<Unit>
    suspend fun shouldShowNotification(peerId: String): Boolean
    suspend fun markNotificationAsShown(peerId: String): Result<Unit>
}