package com.bitchat.android.feature.chat.usersheet.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.nostr.GeohashRepository
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.MessageManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Date

internal class UserSheetStoreFactory : KoinComponent {

    private val storeFactory: StoreFactory by inject()
    private val meshService: BluetoothMeshService by inject()
    private val geohashRepository: GeohashRepository by inject()
    private val dataManager: DataManager by inject()
    private val messageManager: MessageManager by inject()

    fun create(
        targetNickname: String,
        selectedMessage: BitchatMessage?,
        currentNickname: String,
        isGeohashChannel: Boolean
    ): UserSheetStore =
        object : UserSheetStore,
            Store<UserSheetStore.Intent, UserSheetStore.State, UserSheetStore.Label> by storeFactory.create(
                name = "UserSheetStore",
                initialState = UserSheetStore.State(
                    targetNickname = targetNickname,
                    selectedMessage = selectedMessage,
                    isSelf = currentNickname == targetNickname || (selectedMessage?.sender == currentNickname),
                    isGeohashChannel = isGeohashChannel
                ),
                bootstrapper = SimpleBootstrapper(UserSheetStore.Action.Init),
                executorFactory = { ExecutorImpl(targetNickname, isGeohashChannel) },
                reducer = ReducerImpl
            ) {}

    private inner class ExecutorImpl(
        private val targetNickname: String,
        private val isGeohashChannel: Boolean
    ) : CoroutineExecutor<UserSheetStore.Intent, UserSheetStore.Action, UserSheetStore.State, UserSheetStore.Msg, UserSheetStore.Label>() {

        override fun executeAction(action: UserSheetStore.Action) {
            // No init actions needed for now
        }

        override fun executeIntent(intent: UserSheetStore.Intent) {
            when (intent) {
                UserSheetStore.Intent.Slap -> {
                    meshService.sendMessage("/slap $targetNickname")
                    publish(UserSheetStore.Label.Dismiss)
                }
                UserSheetStore.Intent.Hug -> {
                    meshService.sendMessage("/hug $targetNickname")
                    publish(UserSheetStore.Label.Dismiss)
                }
                UserSheetStore.Intent.Block -> {
                    if (isGeohashChannel) {
                        blockUserInGeohash(targetNickname)
                    } else {
                        meshService.sendMessage("/block $targetNickname")
                    }
                    publish(UserSheetStore.Label.Dismiss)
                }
            }
        }

        private fun blockUserInGeohash(targetNickname: String) {
            val pubkey = geohashRepository.findPubkeyByNickname(targetNickname)
            if (pubkey != null) {
                dataManager.addGeohashBlockedUser(pubkey)
                // Refresh people list and counts to remove blocked entry immediately
                geohashRepository.refreshGeohashPeople()
                geohashRepository.updateReactiveParticipantCounts()
                val sysMsg = BitchatMessage(
                    sender = "system",
                    content = "blocked $targetNickname in geohash channels",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(sysMsg)
            } else {
                val sysMsg = BitchatMessage(
                    sender = "system",
                    content = "user '$targetNickname' not found in current geohash",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(sysMsg)
            }
        }
    }

    private object ReducerImpl : Reducer<UserSheetStore.State, UserSheetStore.Msg> {
        override fun UserSheetStore.State.reduce(msg: UserSheetStore.Msg): UserSheetStore.State =
            this // No state changes
    }
}
