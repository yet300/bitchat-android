package com.bitchat.android.feature.chat.passwordprompt.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.bitchat.android.ui.ChatViewModel
import org.koin.core.component.KoinComponent

internal class PasswordPromptStoreFactory(
    private val storeFactory: StoreFactory,
    private val channelName: String,
    private val chatViewModel: ChatViewModel // Temporary dependency
) : KoinComponent {

    fun create(): PasswordPromptStore =
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
                        val success = chatViewModel.joinChannel(state.channelName, state.passwordInput)
                        if (success) {
                            publish(PasswordPromptStore.Label.Dismiss)
                        } else {
                            dispatch(PasswordPromptStore.Msg.ErrorChanged(true))
                        }
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
