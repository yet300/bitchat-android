package com.yet.bitmessage.feature.chats.conversations.connectivity.store

import com.app.domain.model.TransportKind
import com.app.domain.model.TransportStatus
import com.arkivanov.mvikotlin.core.store.Store

internal interface ConnectivityStore :
    Store<ConnectivityStore.Intent, ConnectivityStore.State, ConnectivityStore.Label> {

    data class State(
        val statuses: List<TransportStatus> = emptyList(),
    )

    sealed interface Intent {
        data class Enable(val kind: TransportKind) : Intent
    }

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class Loaded(val statuses: List<TransportStatus>) : Msg
    }

    sealed interface Label
}
