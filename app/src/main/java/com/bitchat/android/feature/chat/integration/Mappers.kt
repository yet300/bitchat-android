package com.bitchat.android.feature.chat.integration

import com.bitchat.android.feature.chat.ChatComponent
import com.bitchat.android.feature.chat.store.ChatStore

/**
 * Maps ChatStore.State to ChatComponent.Model
 * Following the Decompose + MVIKotlin pattern from TetrisLite reference
 */
internal val stateToModel: (ChatStore.State) -> ChatComponent.Model = { state ->
    ChatComponent.Model(
        // Messages
        messages = state.messages,
        channelMessages = state.channelMessages,
        privateChats = state.privateChats,
        
        // Connection state
        isConnected = state.isConnected,
        connectedPeers = state.connectedPeers,
        myPeerID = state.myPeerID,
        nickname = state.nickname,
        
        // Channel state
        joinedChannels = state.joinedChannels,
        currentChannel = state.currentChannel,
        passwordProtectedChannels = state.passwordProtectedChannels,
        unreadChannelMessages = state.unreadChannelMessages,
        hasUnreadChannels = state.hasUnreadChannels,
        
        // Private chat state
        selectedPrivateChatPeer = state.selectedPrivateChatPeer,
        unreadPrivateMessages = state.unreadPrivateMessages,
        hasUnreadPrivateMessages = state.hasUnreadPrivateMessages,
        
        // Location/Geohash state
        selectedLocationChannel = state.selectedLocationChannel,
        isTeleported = state.isTeleported,
        geohashPeople = state.geohashPeople,
        teleportedGeo = state.teleportedGeo,
        geohashParticipantCounts = state.geohashParticipantCounts,
        geohashBookmarks = state.geohashBookmarks,
        geohashBookmarkNames = state.geohashBookmarkNames,
        
        // Peer info
        peerSessionStates = state.peerSessionStates,
        peerFingerprints = state.peerFingerprints,
        peerNicknames = state.peerNicknames,
        peerRSSI = state.peerRSSI,
        peerDirect = state.peerDirect,
        favoritePeers = state.favoritePeers,
        
        // UI state
        showCommandSuggestions = state.showCommandSuggestions,
        commandSuggestions = state.commandSuggestions,
        showMentionSuggestions = state.showMentionSuggestions,
        mentionSuggestions = state.mentionSuggestions,
        
        // Loading states
        isLoading = state.isLoading,
        isSendingMessage = state.isSendingMessage,
        
        // Network state
        torStatus = state.torStatus,
        powEnabled = state.powEnabled,
        powDifficulty = state.powDifficulty,
        isMining = state.isMining,
        
        // Location state
        locationPermissionState = state.locationPermissionState,
        locationServicesEnabled = state.locationServicesEnabled,
        locationNotes = state.locationNotes
    )
}
