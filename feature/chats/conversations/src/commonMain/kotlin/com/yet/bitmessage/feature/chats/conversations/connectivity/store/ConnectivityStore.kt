package com.yet.bitmessage.feature.chats.conversations.connectivity.store

import com.app.domain.model.Fingerprint
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.TransportKind
import com.app.domain.model.TransportStatus
import com.arkivanov.mvikotlin.core.store.Store

internal interface ConnectivityStore :
    Store<ConnectivityStore.Intent, ConnectivityStore.State, ConnectivityStore.Label> {

    data class State(
        val statuses: List<TransportStatus> = emptyList(),
        val peers: List<Peer> = emptyList(),
        val favorites: Set<Fingerprint> = emptySet(),
    )

    sealed interface Intent {
        data class Enable(val kind: TransportKind) : Intent
        data class ToggleFavorite(val peerId: PeerId) : Intent
    }

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class Loaded(val statuses: List<TransportStatus>) : Msg
        data class PeersLoaded(val peers: List<Peer>) : Msg
        data class FavoritesLoaded(val favorites: Set<Fingerprint>) : Msg
    }

    sealed interface Label
}
