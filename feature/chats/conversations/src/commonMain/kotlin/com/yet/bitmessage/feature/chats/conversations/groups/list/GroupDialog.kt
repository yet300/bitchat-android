package com.yet.bitmessage.feature.chats.conversations.groups.list

import com.app.domain.model.Peer

/** Transient overlays for the group list: name a new group, or pick a peer to invite. */
sealed interface GroupDialog {

    data object Create : GroupDialog

    /** Invite picker for [groupIdHex]; shows the currently connected [peers]. */
    data class Invite(val groupIdHex: String, val peers: List<Peer>) : GroupDialog
}
