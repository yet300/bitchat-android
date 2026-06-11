package com.bitchat.android.services

import com.bitchat.android.ui.ChatState

/**
 * UI-state merge helper for alias conversations. Address RESOLUTION lives in
 * [com.app.data.routing.PeerAddressResolver]; this object only rewrites ChatState.
 */
object ConversationAliasResolver {


    fun unifyChatsIntoPeer(
        state: ChatState,
        targetPeerID: String,
        keysToMerge: List<String>
    ) {
        if (keysToMerge.isEmpty()) return
        val currentChats = state.getPrivateChatsValue().toMutableMap()
        val targetList = currentChats[targetPeerID]?.toMutableList() ?: mutableListOf()
        var didMerge = false
        keysToMerge.distinct().forEach { key ->
            if (key == targetPeerID) return@forEach
            val list = currentChats[key]
            if (!list.isNullOrEmpty()) {
                targetList.addAll(list)
                currentChats.remove(key)
                didMerge = true
            }
        }
        if (didMerge) {
            // Preserve arrival order; do not sort by timestamp
            currentChats[targetPeerID] = targetList
            state.setPrivateChats(currentChats)

            // Move unread flags
            val unread = state.getUnreadPrivateMessagesValue().toMutableSet()
            var hadUnread = false
            keysToMerge.forEach { key -> if (unread.remove(key)) hadUnread = true }
            if (hadUnread) unread.add(targetPeerID)
            state.setUnreadPrivateMessages(unread)

            // Switch selection if currently viewing an alias that got merged
            val selected = state.getSelectedPrivateChatPeerValue()
            if (selected != null && keysToMerge.contains(selected)) {
                state.setSelectedPrivateChatPeer(targetPeerID)
            }
            
            // Switch sheet peer if currently viewing an alias that got merged
            val sheetPeer = state.getPrivateChatSheetPeerValue()
            if (sheetPeer != null && keysToMerge.contains(sheetPeer)) {
                state.setPrivateChatSheetPeer(targetPeerID)
            }
        }
    }
}
