package com.yet.bitmessage.feature.chats.conversations.connectivity.store

import com.app.domain.repository.ConnectivityRepository
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch

internal class ConnectivityStoreFactory(
    private val storeFactory: StoreFactory,
    private val connectivityRepository: ConnectivityRepository,
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
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<ConnectivityStore.Intent, ConnectivityStore.Action, ConnectivityStore.State, ConnectivityStore.Msg, ConnectivityStore.Label>() {

        override fun executeAction(action: ConnectivityStore.Action) {
            when (action) {
                ConnectivityStore.Action.Subscribe -> scope.launch {
                    connectivityRepository.observe().collect { dispatch(ConnectivityStore.Msg.Loaded(it)) }
                }
            }
        }

        override fun executeIntent(intent: ConnectivityStore.Intent) {
            when (intent) {
                is ConnectivityStore.Intent.Enable ->
                    scope.launch { connectivityRepository.enable(intent.kind) }
            }
        }
    }
}
