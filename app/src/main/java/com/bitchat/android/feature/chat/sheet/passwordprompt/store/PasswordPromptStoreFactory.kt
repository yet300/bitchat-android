package com.bitchat.android.feature.chat.sheet.passwordprompt.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class PasswordPromptStoreFactory : KoinComponent {

    private val storeFactory: StoreFactory by inject()

    fun create(channelName: String): PasswordPromptStore =
        object : PasswordPromptStore,
            Store<PasswordPromptStore.Intent, PasswordPromptStore.State, PasswordPromptStore.Label> by storeFactory.create(
                name = "PasswordPromptStore",
                initialState = PasswordPromptStore.State(
                    channelName = channelName
                ),
                bootstrapper = SimpleBootstrapper(PasswordPromptStore.Action.Init),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl
            ) {}

    private inner class ExecutorImpl :
        CoroutineExecutor<PasswordPromptStore.Intent, PasswordPromptStore.Action, PasswordPromptStore.State, PasswordPromptStore.Msg, PasswordPromptStore.Label>() {

        override fun executeAction(action: PasswordPromptStore.Action) {
            // No init actions needed
        }

        override fun executeIntent(intent: PasswordPromptStore.Intent) {
            when (intent) {
                is PasswordPromptStore.Intent.SetPassword -> {
                    dispatch(PasswordPromptStore.Msg.PasswordChanged(intent.password))
                    dispatch(PasswordPromptStore.Msg.ErrorChanged(false))
                }
                PasswordPromptStore.Intent.Confirm -> {
                    val state = state()
                    if (state.passwordInput.isNotEmpty()) {
                        // Emit label for parent component to handle channel join
                        publish(PasswordPromptStore.Label.SubmitPassword(state.channelName, state.passwordInput))
                    }
                }
            }
        }
    }

    private object ReducerImpl : Reducer<PasswordPromptStore.State, PasswordPromptStore.Msg> {
        override fun PasswordPromptStore.State.reduce(msg: PasswordPromptStore.Msg): PasswordPromptStore.State =
            when (msg) {
                is PasswordPromptStore.Msg.PasswordChanged -> copy(passwordInput = msg.password)
                is PasswordPromptStore.Msg.ErrorChanged -> copy(isError = msg.isError)
            }
    }
}
