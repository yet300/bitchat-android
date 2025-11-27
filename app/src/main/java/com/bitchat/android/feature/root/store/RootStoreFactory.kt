package com.bitchat.android.feature.root.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.bitchat.android.ui.theme.ThemePreferenceManager
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class RootStoreFactory(
    private val storeFactory: StoreFactory
) : KoinComponent {

    private val themePreferenceManager: ThemePreferenceManager by inject()

    fun create(): RootStore =
        object : RootStore,
            Store<RootStore.Intent, RootStore.State, RootStore.Label> by storeFactory.create(
                name = "RootStore",
                initialState = RootStore.State(
                    themePreference = themePreferenceManager.themeFlow.value
                ),
                bootstrapper = SimpleBootstrapper(RootStore.Action.Init),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl
            ) {}

    private inner class ExecutorImpl :
        CoroutineExecutor<RootStore.Intent, RootStore.Action, RootStore.State, RootStore.Msg, RootStore.Label>() {

        override fun executeAction(action: RootStore.Action) {
            when (action) {
                RootStore.Action.Init -> {
                    scope.launch {
                        themePreferenceManager.themeFlow.collect {
                            dispatch(RootStore.Msg.ThemeChanged(it))
                        }
                    }
                }
            }
        }

        override fun executeIntent(intent: RootStore.Intent) {
            when (intent) {
                is RootStore.Intent.SetTheme -> {
                    themePreferenceManager.set(intent.theme)
                }
            }
        }
    }

    private object ReducerImpl : Reducer<RootStore.State, RootStore.Msg> {
        override fun RootStore.State.reduce(msg: RootStore.Msg): RootStore.State =
            when (msg) {
                is RootStore.Msg.ThemeChanged -> copy(themePreference = msg.theme)
            }
    }
}
