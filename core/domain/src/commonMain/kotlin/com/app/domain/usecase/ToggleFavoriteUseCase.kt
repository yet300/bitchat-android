package com.app.domain.usecase

import com.app.domain.model.PeerId
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.MessageTransport

/** Toggle favorite and notify the peer (mesh/Nostr — decided by the transport). */
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
