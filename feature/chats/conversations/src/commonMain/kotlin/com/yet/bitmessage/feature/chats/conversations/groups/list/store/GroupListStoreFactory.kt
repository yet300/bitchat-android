package com.yet.bitmessage.feature.chats.conversations.groups.list.store

import com.app.domain.repository.GroupRepository
import com.app.domain.repository.PeerRepository
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch

internal class GroupListStoreFactory(
    private val storeFactory: StoreFactory,
    private val groupRepository: GroupRepository,
    private val peerRepository: PeerRepository,
) {
    fun create(): GroupListStore =
        object : GroupListStore,
            Store<GroupListStore.Intent, GroupListStore.State, Nothing> by storeFactory.create(
                name = "GroupListStore",
                initialState = GroupListStore.State(),
                bootstrapper = SimpleBootstrapper(GroupListStore.Action.Subscribe),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<GroupListStore.State, GroupListStore.Msg> {
        override fun GroupListStore.State.reduce(msg: GroupListStore.Msg): GroupListStore.State =
            when (msg) {
                is GroupListStore.Msg.GroupsLoaded -> copy(isLoading = false, groups = msg.groups)
                is GroupListStore.Msg.PeersLoaded -> copy(invitablePeers = msg.peers)
                is GroupListStore.Msg.Error -> copy(isLoading = false, error = msg.message)
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<GroupListStore.Intent, GroupListStore.Action, GroupListStore.State, GroupListStore.Msg, Nothing>() {

        override fun executeAction(action: GroupListStore.Action) {
            when (action) {
                GroupListStore.Action.Subscribe -> {
                    reloadGroups()
                    // Membership can change under us (an accepted invite, a rotation) — re-list on traffic.
                    scope.launch {
                        groupRepository.incomingMessages.collect { reloadGroups() }
                    }
                    scope.launch {
                        peerRepository.observePeers().collect { peers ->
                            dispatch(GroupListStore.Msg.PeersLoaded(peers.filter { it.isConnected }))
                        }
                    }
                }
            }
        }

        override fun executeIntent(intent: GroupListStore.Intent) {
            when (intent) {
                is GroupListStore.Intent.Create -> scope.launch {
                    groupRepository.createGroup(intent.name)
                    reloadGroups()
                }
                is GroupListStore.Intent.Invite -> scope.launch {
                    groupRepository.invite(intent.groupIdHex, intent.peerId)
                    reloadGroups()
                }
                is GroupListStore.Intent.Leave -> scope.launch {
                    groupRepository.leave(intent.groupIdHex)
                    reloadGroups()
                }
            }
        }

        private fun reloadGroups() = scope.launch {
            dispatch(GroupListStore.Msg.GroupsLoaded(groupRepository.listGroups()))
        }
    }
}
