package com.bitchat.android.feature.chat.meshpeerlist

import androidx.compose.ui.graphics.Color
import com.arkivanov.decompose.value.Value
import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.GeoPerson

interface MeshPeerListComponent {
    val model: Value<Model>
    
    // Actions
    fun onDismiss()
    fun onSwitchToChannel(channel: String)
    fun onLeaveChannel(channel: String)
    fun onStartPrivateChat(peerID: String)
    fun onEndPrivateChat()
    fun onStartGeohashDM(nostrPubkey: String)
    fun onToggleFavorite(peerID: String)
    
    // Peer info queries
    fun isPersonTeleported(nostrPubkey: String): Boolean
    fun colorForNostrPubkey(pubkey: String, isDark: Boolean): Color
    fun getPeerNoisePublicKeyHex(peerID: String): String?
    fun getOfflineFavorites(): List<FavoriteRelationship>
    fun findNostrPubkey(noiseKey: ByteArray): String?
    fun isPeerDirectConnection(peerID: String): Boolean
    fun colorForMeshPeer(peerID: String, isDark: Boolean): Color

    data class Model(
        // Connection state
        val connectedPeers: List<String>,
        val myPeerID: String,
        val nickname: String,
        
        // Channel state
        val joinedChannels: Set<String>,
        val currentChannel: String?,
        val unreadChannelMessages: Map<String, Int>,
        
        // Private chat state
        val selectedPrivatePeer: String?,
        val unreadPrivateMessages: Set<String>,
        val privateChats: Map<String, List<BitchatMessage>>,
        
        // Location/Geohash state
        val selectedLocationChannel: ChannelID?,
        val geohashPeople: List<GeoPerson>,
        val isTeleported: Boolean,
        
        // Peer info
        val peerNicknames: Map<String, String>,
        val peerRSSI: Map<String, Int>,
        val peerDirect: Map<String, Boolean>,
        val peerFingerprints: Map<String, String>,
        val peerSessionStates: Map<String, String>,
        val favoritePeers: Set<String>,
        
        // Offline favorites
        val offlineFavorites: List<FavoriteRelationship>
    )
}
