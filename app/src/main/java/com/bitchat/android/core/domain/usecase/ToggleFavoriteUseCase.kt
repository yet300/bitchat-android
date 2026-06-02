package com.bitchat.android.core.domain.usecase

import com.bitchat.android.core.domain.model.PeerId
import com.bitchat.android.core.domain.repository.ContactRepository
import com.bitchat.android.core.domain.repository.MessageTransport

/** Переключить избранное и уведомить собеседника (mesh/Nostr — решает транспорт). */
class ToggleFavoriteUseCase(
    private val contacts: ContactRepository,
    private val transport: MessageTransport,
) {
    suspend operator fun invoke(peer: PeerId) {
        contacts.toggleFavorite(peer)
        val isNowFavorite = contacts.isFavorite(peer)
        transport.sendFavoriteNotification(peer, isNowFavorite)
    }
}
