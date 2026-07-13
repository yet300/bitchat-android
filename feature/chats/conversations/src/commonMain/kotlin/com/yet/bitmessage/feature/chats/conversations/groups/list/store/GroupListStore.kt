package com.yet.bitmessage.feature.chats.conversations.groups.list.store

import com.app.domain.model.GroupInfo
import com.app.domain.model.Peer
import com.arkivanov.mvikotlin.core.store.Store

internal interface GroupListStore :
    Store<GroupListStore.Intent, GroupListStore.State, Nothing> {

    data class State(
        val isLoading: Boolean = true,
        val groups: List<GroupInfo> = emptyList(),
        /** Connected peers eligible to be invited (creator-only action). */
        val invitablePeers: List<Peer> = emptyList(),
        val error: String? = null,
    )

    sealed interface Intent {
        data class Create(val name: String) : Intent
        data class Invite(val groupIdHex: String, val peerId: String) : Intent
        data class Leave(val groupIdHex: String) : Intent
    }

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class GroupsLoaded(val groups: List<GroupInfo>) : Msg
        data class PeersLoaded(val peers: List<Peer>) : Msg
        data class Error(val message: String?) : Msg
    }
}
