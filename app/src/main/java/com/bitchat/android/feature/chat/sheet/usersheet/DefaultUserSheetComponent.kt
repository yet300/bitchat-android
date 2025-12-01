package com.bitchat.android.feature.chat.sheet.usersheet

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.bitchat.android.core.common.asValue
import com.bitchat.android.core.common.coroutineScope
import com.bitchat.android.feature.chat.sheet.usersheet.integration.stateToModel
import com.bitchat.android.feature.chat.sheet.usersheet.store.UserSheetStore
import com.bitchat.android.feature.chat.sheet.usersheet.store.UserSheetStoreFactory
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.launch

class DefaultUserSheetComponent(
    componentContext: ComponentContext,
    private val targetNickname: String,
    private val selectedMessage: BitchatMessage?,
    private val currentNickname: String,
    private val isGeohashChannel: Boolean,
    private val onDismissCallback: () -> Unit
) : UserSheetComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore {
        UserSheetStoreFactory().create(
            targetNickname = targetNickname,
            selectedMessage = selectedMessage,
            currentNickname = currentNickname,
            isGeohashChannel = isGeohashChannel
        )
    }

    init {
        coroutineScope().launch {
            store.labels.collect { label ->
                when (label) {
                    UserSheetStore.Label.Dismiss -> onDismissCallback()
                }
            }
        }
    }

    override val model: Value<UserSheetComponent.Model> = store.asValue().map(stateToModel)

    override fun onSlap() {
        store.accept(UserSheetStore.Intent.Slap)
    }

    override fun onHug() {
        store.accept(UserSheetStore.Intent.Hug)
    }

    override fun onBlock() {
        store.accept(UserSheetStore.Intent.Block)
    }

    override fun onDismiss() {
        onDismissCallback()
    }
}
