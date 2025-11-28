package com.bitchat.android.feature.chat.usersheet.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.ChatViewModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class UserSheetStoreFactory(
    private val storeFactory: StoreFactory,
    private val targetNickname: String,
    private val selectedMessage: BitchatMessage?,
    private val chatViewModel: ChatViewModel // Temporary dependency
) : KoinComponent {

    fun create(): UserSheetStore =
        object : UserSheetStore,
            Store<UserSheetStore.Intent, UserSheetStore.State, UserSheetStore.Label> by storeFactory.create(
                name = "UserSheetStore",
                initialState = UserSheetStore.State(
                    targetNickname = targetNickname,
                    selectedMessage = selectedMessage,
                    isSelf = chatViewModel.nickname.value == targetNickname || (selectedMessage?.sender == chatViewModel.nickname.value),
                    isGeohashChannel = chatViewModel.selectedLocationChannel.value is com.bitchat.android.geohash.ChannelID.Location
                ),
                bootstrapper = SimpleBootstrapper(UserSheetStore.Action.Init),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl
            ) {}

    private inner class ExecutorImpl :
        CoroutineExecutor<UserSheetStore.Intent, UserSheetStore.Action, UserSheetStore.State, UserSheetStore.Msg, UserSheetStore.Label>() {

        override fun executeAction(action: UserSheetStore.Action) {
            // No init actions needed for now
        }

        override fun executeIntent(intent: UserSheetStore.Intent) {
            when (intent) {
                UserSheetStore.Intent.Slap -> {
                    chatViewModel.sendMessage("/slap $targetNickname")
                    publish(UserSheetStore.Label.Dismiss)
                }
                UserSheetStore.Intent.Hug -> {
                    chatViewModel.sendMessage("/hug $targetNickname")
                    publish(UserSheetStore.Label.Dismiss)
                }
                UserSheetStore.Intent.Block -> {
                    val state = state()
                    if (state.isGeohashChannel) {
                        chatViewModel.blockUserInGeohash(targetNickname)
                    } else {
                        chatViewModel.sendMessage("/block $targetNickname")
                    }
                    publish(UserSheetStore.Label.Dismiss)
                }
            }
        }
    }

    private object ReducerImpl : Reducer<UserSheetStore.State, UserSheetStore.Msg> {
        override fun UserSheetStore.State.reduce(msg: UserSheetStore.Msg): UserSheetStore.State =
            this // No state changes
    }
}
