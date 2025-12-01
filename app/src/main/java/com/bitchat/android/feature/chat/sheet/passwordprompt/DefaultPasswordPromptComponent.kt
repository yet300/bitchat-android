package com.bitchat.android.feature.chat.sheet.passwordprompt

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.bitchat.android.core.common.asValue
import com.bitchat.android.core.common.coroutineScope
import com.bitchat.android.feature.chat.sheet.passwordprompt.integration.stateToModel
import com.bitchat.android.feature.chat.sheet.passwordprompt.store.PasswordPromptStore
import com.bitchat.android.feature.chat.sheet.passwordprompt.store.PasswordPromptStoreFactory
import kotlinx.coroutines.launch

class DefaultPasswordPromptComponent(
    componentContext: ComponentContext,
    private val channelName: String,
    private val onDismissCallback: () -> Unit
) : PasswordPromptComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore {
        PasswordPromptStoreFactory().create(
            channelName = channelName
        )
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
