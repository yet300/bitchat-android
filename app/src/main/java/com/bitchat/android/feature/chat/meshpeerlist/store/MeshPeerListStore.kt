package com.bitchat.android.feature.chat.meshpeerlist.store

import com.arkivanov.mvikotlin.core.store.Store
import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.GeoPerson

/**
 * MVIKotlin Store for MeshPeerList feature
 */
internal interface MeshPeerListStore : Store<MeshPeerListStore.Intent, MeshPeerListStore.State, MeshPeerListStore.Label> {

    sealed interface Intent {
        data class SwitchToChannel(val channel: String) : Intent
        data class LeaveChannel(val channel: String) : Intent
        data class StartPrivateChat(val peerID: String) : Intent
        data object EndPrivateChat : Intent
        data class StartGeohashDM(val nostrPubkey: String) : Intent
        data class ToggleFavorite(val peerID: String) : Intent
    }

    data class State(
        // Connection state
        val connectedPeers: List<String> = emptyList(),
        val myPeerID: String = "",
        val nickname: String = "",
        
        // Channel state
        val joinedChannels: Set<String> = emptySet(),
        val currentChannel: String? = null,
        val unreadChannelMessages: Map<String, Int> = emptyMap(),
        
        // Private chat state
        val selectedPrivatePeer: String? = null,
        val unreadPrivateMessages: Set<String> = emptySet(),
        val privateChats: Map<String, List<BitchatMessage>> = emptyMap(),
        
        // Location/Geohash state
        val selectedLocationChannel: ChannelID? = null,
        val geohashPeople: List<GeoPerson> = emptyList(),
        val isTeleported: Boolean = false,
        val teleportedGeo: Set<String> = emptySet(),
        
        // Peer info
        val peerNicknames: Map<String, String> = emptyMap(),
        val peerRSSI: Map<String, Int> = emptyMap(),
        val peerDirect: Map<String, Boolean> = emptyMap(),
        val peerFingerprints: Map<String, String> = emptyMap(),
        val peerSessionStates: Map<String, String> = emptyMap(),
        val favoritePeers: Set<String> = emptySet(),
        
        // Offline favorites
        val offlineFavorites: List<FavoriteRelationship> = emptyList()
    )

    sealed interface Label {
        data object Dismissed : Label
        data class ChannelSwitched(val channel: String) : Label
        data class PrivateChatStarted(val peerID: String) : Label
    }

    sealed interface Msg {
        data class ConnectedPeersUpdated(val peers: List<String>) : Msg
        data class JoinedChannelsUpdated(val channels: Set<String>) : Msg
        data class CurrentChannelChanged(val channel: String?) : Msg
        data class UnreadChannelMessagesUpdated(val unread: Map<String, Int>) : Msg
        data class SelectedPrivatePeerChanged(val peer: String?) : Msg
        data class UnreadPrivateMessagesUpdated(val unread: Set<String>) : Msg
        data class PrivateChatsUpdated(val chats: Map<String, List<BitchatMessage>>) : Msg
        data class SelectedLocationChannelChanged(val channel: ChannelID?) : Msg
        data class GeohashPeopleUpdated(val people: List<GeoPerson>) : Msg
        data class TeleportedStateChanged(val teleported: Boolean) : Msg
        data class TeleportedGeoUpdated(val geo: Set<String>) : Msg
        data class PeerNicknamesUpdated(val nicknames: Map<String, String>) : Msg
        data class PeerRSSIUpdated(val rssi: Map<String, Int>) : Msg
        data class PeerDirectUpdated(val direct: Map<String, Boolean>) : Msg
        data class PeerFingerprintsUpdated(val fingerprints: Map<String, String>) : Msg
        data class PeerSessionStatesUpdated(val states: Map<String, String>) : Msg
        data class FavoritePeersUpdated(val favorites: Set<String>) : Msg
        data class OfflineFavoritesUpdated(val favorites: List<FavoriteRelationship>) : Msg
        data class NicknameChanged(val nickname: String) : Msg
        data class MyPeerIDUpdated(val peerID: String) : Msg
    }
}
