package com.bitchat.android.feature.chat

import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.Value
import com.bitchat.android.feature.about.AboutComponent
import com.bitchat.android.feature.chat.locationchannels.LocationChannelsComponent
import com.bitchat.android.feature.chat.locationnotes.LocationNotesComponent
import com.bitchat.android.feature.chat.meshpeerlist.MeshPeerListComponent
import com.bitchat.android.feature.debug.DebugComponent
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.CommandSuggestion
import com.bitchat.android.ui.GeoPerson
import androidx.compose.ui.graphics.Color

interface ChatComponent {
    val model: Value<Model>
    val sheetSlot: Value<ChildSlot<*, SheetChild>>
    val dialogSlot: Value<ChildSlot<*, DialogChild>>
    
    // Sheet/Dialog navigation
    fun onDismissSheet()
    fun onShowAppInfo()
    fun onShowLocationChannels()
    fun onShowLocationNotes()
    fun onShowUserSheet(nickname: String, messageId: String?)
    fun onShowMeshPeerList()
    fun onShowDebugSettings()
    fun onDismissDialog()
    fun onShowPasswordPrompt(channelName: String)
    
    // Message actions
    fun onSendMessage(content: String)
    fun onSendVoiceNote(toPeerID: String?, channel: String?, filePath: String)
    fun onSendImageNote(toPeerID: String?, channel: String?, filePath: String)
    fun onSendFileNote(toPeerID: String?, channel: String?, filePath: String)
    fun onCancelMediaSend(messageId: String)
    
    // Channel actions
    fun onJoinChannel(channel: String, password: String? = null)
    fun onSwitchToChannel(channel: String?)
    fun onLeaveChannel(channel: String)
    
    // Private chat actions
    fun onStartPrivateChat(peerID: String)
    fun onEndPrivateChat()
    fun onOpenLatestUnreadPrivateChat()
    
    // Location channel actions
    fun onSelectLocationChannel(channelID: ChannelID?)
    
    // Peer actions
    fun onToggleFavorite(peerID: String)
    fun onSetNickname(nickname: String)
    fun onStartGeohashDM(nostrPubkey: String)
    
    // Peer info queries (for UI callbacks)
    fun isPersonTeleported(nostrPubkey: String): Boolean
    fun colorForNostrPubkey(pubkey: String, isDark: Boolean): Color
    fun getPeerNoisePublicKeyHex(peerID: String): String?
    fun getOfflineFavorites(): List<com.bitchat.android.favorites.FavoriteRelationship>
    fun findNostrPubkey(noiseKey: ByteArray): String?
    fun isPeerDirectConnection(peerID: String): Boolean
    fun colorForMeshPeer(peerID: String, isDark: Boolean): Color
    fun getFavoriteStatus(peerID: String): com.bitchat.android.favorites.FavoriteRelationship?
    
    // Password prompt
    fun onSubmitChannelPassword(channel: String, password: String)
    
    // App lifecycle
    fun onSetAppBackgroundState(isBackground: Boolean)
    
    // Notification management
    fun onClearNotificationsForSender(senderID: String)
    fun onClearNotificationsForGeohash(geohash: String)
    
    // Command/Mention suggestions
    fun onUpdateCommandSuggestions(input: String)
    fun onSelectCommandSuggestion(suggestion: CommandSuggestion): String
    fun onUpdateMentionSuggestions(input: String)
    fun onSelectMentionSuggestion(nickname: String, currentText: String): String
    
    // Geohash actions
    fun onTeleportToGeohash(geohash: String)
    fun onRefreshLocationChannels()
    fun onToggleGeohashBookmark(geohash: String)
    
    // Emergency actions
    fun onPanicClearAllData()

    /**
     * Model representing the UI state for the Chat screen
     */
    data class Model(
        // Messages
        val messages: List<BitchatMessage> = emptyList(),
        val channelMessages: Map<String, List<BitchatMessage>> = emptyMap(),
        val privateChats: Map<String, List<BitchatMessage>> = emptyMap(),
        
        // Connection state
        val isConnected: Boolean = false,
        val connectedPeers: List<String> = emptyList(),
        val myPeerID: String = "",
        val nickname: String = "",
        
        // Channel state
        val joinedChannels: Set<String> = emptySet(),
        val currentChannel: String? = null,
        val passwordProtectedChannels: Set<String> = emptySet(),
        val unreadChannelMessages: Map<String, Int> = emptyMap(),
        val hasUnreadChannels: Boolean = false,
        
        // Private chat state
        val selectedPrivateChatPeer: String? = null,
        val unreadPrivateMessages: Set<String> = emptySet(),
        val hasUnreadPrivateMessages: Boolean = false,
        
        // Location/Geohash state
        val selectedLocationChannel: ChannelID? = null,
        val isTeleported: Boolean = false,
        val geohashPeople: List<GeoPerson> = emptyList(),
        val teleportedGeo: Set<String> = emptySet(),
        val geohashParticipantCounts: Map<String, Int> = emptyMap(),
        val geohashBookmarks: List<String> = emptyList(),
        val geohashBookmarkNames: Map<String, String> = emptyMap(),
        
        // Peer info
        val peerSessionStates: Map<String, String> = emptyMap(),
        val peerFingerprints: Map<String, String> = emptyMap(),
        val peerNicknames: Map<String, String> = emptyMap(),
        val peerRSSI: Map<String, Int> = emptyMap(),
        val peerDirect: Map<String, Boolean> = emptyMap(),
        val favoritePeers: Set<String> = emptySet(),
        
        // UI state
        val showCommandSuggestions: Boolean = false,
        val commandSuggestions: List<CommandSuggestion> = emptyList(),
        val showMentionSuggestions: Boolean = false,
        val mentionSuggestions: List<String> = emptyList(),
        
        // Loading states
        val isLoading: Boolean = true,
        val isSendingMessage: Boolean = false,
        
        // Network state
        val torStatus: com.bitchat.android.net.TorManager.TorStatus = com.bitchat.android.net.TorManager.TorStatus(),
        val powEnabled: Boolean = false,
        val powDifficulty: Int = 0,
        val isMining: Boolean = false,
        
        // Location state
        val locationPermissionState: com.bitchat.android.geohash.LocationChannelManager.PermissionState = com.bitchat.android.geohash.LocationChannelManager.PermissionState.NOT_DETERMINED,
        val locationServicesEnabled: Boolean = false,
        val locationNotes: List<com.bitchat.android.nostr.LocationNotesManager.Note> = emptyList()
    )

    sealed interface ChatStartupConfig {
        data object Default : ChatStartupConfig
        data class PrivateChat(val peerId: String) : ChatStartupConfig
        data class GeohashChat(val geohash: String) : ChatStartupConfig
    }
    
    sealed interface SheetChild {
        data class AppInfo(val component: AboutComponent) : SheetChild
        data class LocationChannels(val component: LocationChannelsComponent) : SheetChild
        data class LocationNotes(val component: LocationNotesComponent) : SheetChild
        data class UserSheet(val component: com.bitchat.android.feature.chat.usersheet.UserSheetComponent) : SheetChild
        data class MeshPeerList(val component: MeshPeerListComponent) : SheetChild
        data class DebugSettings(val component: DebugComponent) : SheetChild
    }
    
    sealed interface DialogChild {
        data class PasswordPrompt(val component: com.bitchat.android.feature.chat.passwordprompt.PasswordPromptComponent) : DialogChild
    }
}
