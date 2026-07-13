@file:OptIn(ExperimentalTime::class)

package com.yet.bitmessage.feature.chats.conversations.groups.chat.store

import com.app.domain.repository.GroupRepository
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal class GroupChatStoreFactory(
    private val storeFactory: StoreFactory,
    private val groupRepository: GroupRepository,
    private val groupIdHex: String,
) {
    fun create(): GroupChatStore =
        object : GroupChatStore,
            Store<GroupChatStore.Intent, GroupChatStore.State, Nothing> by storeFactory.create(
                name = "GroupChatStore",
                initialState = GroupChatStore.State(),
                bootstrapper = SimpleBootstrapper(GroupChatStore.Action.Subscribe),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<GroupChatStore.State, GroupChatStore.Msg> {
        override fun GroupChatStore.State.reduce(msg: GroupChatStore.Msg): GroupChatStore.State =
            when (msg) {
                is GroupChatStore.Msg.Appended -> copy(messages = messages + msg.message)
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<GroupChatStore.Intent, GroupChatStore.Action, GroupChatStore.State, GroupChatStore.Msg, Nothing>() {

        override fun executeAction(action: GroupChatStore.Action) {
            when (action) {
                GroupChatStore.Action.Subscribe -> scope.launch {
                    groupRepository.incomingMessages
                        .filter { it.groupIdHex == groupIdHex }
                        .collect { event ->
                            dispatch(
                                GroupChatStore.Msg.Appended(
                                    GroupChatStore.GroupChatMessage(
                                        id = event.messageId,
                                        senderNickname = event.senderNickname,
                                        content = event.content,
                                        timestampMs = event.timestampMs,
                                        isMine = false,
                                    ),
                                ),
                            )
                        }
                }
            }
        }

        override fun executeIntent(intent: GroupChatStore.Intent) {
            when (intent) {
                is GroupChatStore.Intent.Send -> scope.launch {
                    val sent = groupRepository.sendMessage(groupIdHex, intent.content)
                    // The repository excludes our own echoes from incomingMessages, so mirror it locally.
                    if (sent) {
                        dispatch(
                            GroupChatStore.Msg.Appended(
                                GroupChatStore.GroupChatMessage(
                                    id = "local-" + Clock.System.now().toEpochMilliseconds(),
                                    senderNickname = "",
                                    content = intent.content,
                                    timestampMs = Clock.System.now().toEpochMilliseconds(),
                                    isMine = true,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}
