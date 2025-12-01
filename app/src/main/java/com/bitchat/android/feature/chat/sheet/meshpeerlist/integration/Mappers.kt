package com.bitchat.android.feature.chat.sheet.meshpeerlist.integration

import com.bitchat.android.feature.chat.sheet.meshpeerlist.MeshPeerListComponent
import com.bitchat.android.feature.chat.sheet.meshpeerlist.store.MeshPeerListStore

internal val stateToModel: (MeshPeerListStore.State) -> MeshPeerListComponent.Model = { state ->
    MeshPeerListComponent.Model(
        connectedPeers = state.connectedPeers,
        myPeerID = state.myPeerID,
        nickname = state.nickname,
        joinedChannels = state.joinedChannels,
        currentChannel = state.currentChannel,
        unreadChannelMessages = state.unreadChannelMessages,
        selectedPrivatePeer = state.selectedPrivatePeer,
        unreadPrivateMessages = state.unreadPrivateMessages,
        privateChats = state.privateChats,
        selectedLocationChannel = state.selectedLocationChannel,
        geohashPeople = state.geohashPeople,
        isTeleported = state.isTeleported,
        peerNicknames = state.peerNicknames,
        peerRSSI = state.peerRSSI,
        peerDirect = state.peerDirect,
        peerFingerprints = state.peerFingerprints,
        peerSessionStates = state.peerSessionStates,
        favoritePeers = state.favoritePeers,
        offlineFavorites = state.offlineFavorites
    )
}
