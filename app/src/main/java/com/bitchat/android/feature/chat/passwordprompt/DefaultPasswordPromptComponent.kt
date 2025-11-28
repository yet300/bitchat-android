package com.bitchat.android.feature.chat.passwordprompt

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.bitchat.android.core.common.asValue
import com.bitchat.android.core.common.coroutineScope
import com.bitchat.android.feature.chat.passwordprompt.integration.stateToModel
import com.bitchat.android.feature.chat.passwordprompt.store.PasswordPromptStore
import com.bitchat.android.feature.chat.passwordprompt.store.PasswordPromptStoreFactory
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DefaultPasswordPromptComponent(
    componentContext: ComponentContext,
    private val channelName: String,
    private val chatViewModel: ChatViewModel, // Temporary dependency
    private val onDismissCallback: () -> Unit
) : PasswordPromptComponent, ComponentContext by componentContext, KoinComponent {

    private val storeFactory: StoreFactory by inject()

    private val store = instanceKeeper.getStore {
        PasswordPromptStoreFactory(
            storeFactory = storeFactory,
            channelName = channelName,
            chatViewModel = chatViewModel
        ).create()
    }

    init {
        coroutineScope().launch {
            store.labels.collect { label ->
                when (label) {
                    PasswordPromptStore.Label.Dismiss -> onDismissCallback()
                }
            }
        }
    }

    override val model: Value<PasswordPromptComponent.Model> = store.asValue().map(stateToModel)

    override fun onPasswordChange(password: String) {
        store.accept(PasswordPromptStore.Intent.SetPassword(password))
    }

    override fun onConfirm() {
        store.accept(PasswordPromptStore.Intent.Confirm)
    }

    override fun onDismiss() {
        onDismissCallback()
    }
}
