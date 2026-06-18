package com.yet.bitmessage.feature.chats.conversations.connectivity.store

import com.app.domain.repository.ConnectivityRepository
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.PeerRepository
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch

internal class ConnectivityStoreFactory(
    private val storeFactory: StoreFactory,
    private val connectivityRepository: ConnectivityRepository,
    private val peerRepository: PeerRepository,
    private val contactRepository: ContactRepository,
) {
    fun create(): ConnectivityStore =
        object : ConnectivityStore,
            Store<ConnectivityStore.Intent, ConnectivityStore.State, ConnectivityStore.Label> by storeFactory.create(
                name = "ConnectivityStore",
                initialState = ConnectivityStore.State(),
                bootstrapper = SimpleBootstrapper(ConnectivityStore.Action.Subscribe),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<ConnectivityStore.State, ConnectivityStore.Msg> {
        override fun ConnectivityStore.State.reduce(msg: ConnectivityStore.Msg): ConnectivityStore.State =
            when (msg) {
                is ConnectivityStore.Msg.Loaded -> copy(statuses = msg.statuses)
                is ConnectivityStore.Msg.PeersLoaded -> copy(peers = msg.peers)
                is ConnectivityStore.Msg.FavoritesLoaded -> copy(favorites = msg.favorites)
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<ConnectivityStore.Intent, ConnectivityStore.Action, ConnectivityStore.State, ConnectivityStore.Msg, ConnectivityStore.Label>() {

        override fun executeAction(action: ConnectivityStore.Action) {
            when (action) {
                ConnectivityStore.Action.Subscribe -> {
                    scope.launch {
                        connectivityRepository.observe().collect { dispatch(ConnectivityStore.Msg.Loaded(it)) }
                    }
                    scope.launch {
                        peerRepository.observePeers().collect { dispatch(ConnectivityStore.Msg.PeersLoaded(it)) }
                    }
                    scope.launch {
                        contactRepository.observeFavorites().collect { dispatch(ConnectivityStore.Msg.FavoritesLoaded(it)) }
                    }
                }
            }
        }

        override fun executeIntent(intent: ConnectivityStore.Intent) {
            when (intent) {
                is ConnectivityStore.Intent.Enable ->
                    scope.launch { connectivityRepository.enable(intent.kind) }
                is ConnectivityStore.Intent.ToggleFavorite ->
                    scope.launch { contactRepository.toggleFavorite(intent.peerId) }
            }
        }
    }
}
