package com.bitchat.android.feature.chat

import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.Value
import com.bitchat.android.feature.chat.sheet.about.AboutComponent
import com.bitchat.android.feature.chat.sheet.debug.DebugComponent
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.CommandSuggestion
import com.bitchat.android.ui.GeoPerson
import com.bitchat.android.feature.chat.sheet.locationchannels.LocationChannelsComponent
import com.bitchat.android.feature.chat.sheet.locationnotes.LocationNotesComponent
import com.bitchat.android.feature.chat.sheet.meshpeerlist.MeshPeerListComponent
import com.bitchat.android.feature.chat.sheet.passwordprompt.PasswordPromptComponent
import com.bitchat.android.feature.chat.sheet.usersheet.UserSheetComponent
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.net.TorManager
import com.bitchat.android.nostr.LocationNotesManager

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
    fun onJoinChannel(channel: String, password: String?)
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

    fun getPeerNoisePublicKeyHex(peerID: String): String?
    fun getOfflineFavorites(): List<com.bitchat.android.favorites.FavoriteRelationship>
    fun findNostrPubkey(noiseKey: ByteArray): String?
    fun isPeerDirectConnection(peerID: String): Boolean

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
        val messages: List<BitchatMessage>,
        val channelMessages: Map<String, List<BitchatMessage>>,
        val privateChats: Map<String, List<BitchatMessage>>,
        
        // Connection state
        val isConnected: Boolean,
        val connectedPeers: List<String>,
        val myPeerID: String = "",
        val nickname: String = "",
        
        // Channel state
        val joinedChannels: Set<String>,
        val currentChannel: String?,
        val passwordProtectedChannels: Set<String>,
        val unreadChannelMessages: Map<String, Int>,
        val hasUnreadChannels: Boolean,
        
        // Private chat state
        val selectedPrivateChatPeer: String?,
        val unreadPrivateMessages: Set<String>,
        val hasUnreadPrivateMessages: Boolean,
        
        // Location/Geohash state
        val selectedLocationChannel: ChannelID?,
        val isTeleported: Boolean,
        val geohashPeople: List<GeoPerson>,
        val teleportedGeo: Set<String>,
        val geohashParticipantCounts: Map<String, Int>,
        val geohashBookmarks: List<String>,
        val geohashBookmarkNames: Map<String, String>,
        
        // Peer info
        val peerSessionStates: Map<String, String>,
        val peerFingerprints: Map<String, String>,
        val peerNicknames: Map<String, String>,
        val peerRSSI: Map<String, Int>,
        val peerDirect: Map<String, Boolean>,
        val favoritePeers: Set<String>,
        
        // UI state
        val showCommandSuggestions: Boolean,
        val commandSuggestions: List<CommandSuggestion>,
        val showMentionSuggestions: Boolean,
        val mentionSuggestions: List<String>,
        
        // Loading states
        val isLoading: Boolean,
        val isSendingMessage: Boolean,
        
        // Network state
        val torStatus: TorManager.TorStatus,
        val powEnabled: Boolean,
        val powDifficulty: Int,
        val isMining: Boolean,
        
        // Location state
        val locationPermissionState: LocationChannelManager.PermissionState,
        val locationServicesEnabled: Boolean,
        val locationNotes: List<LocationNotesManager.Note>
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
        data class UserSheet(val component: UserSheetComponent) : SheetChild
        data class MeshPeerList(val component: MeshPeerListComponent) : SheetChild
        data class DebugSettings(val component: DebugComponent) : SheetChild
    }
    
    sealed interface DialogChild {
        data class PasswordPrompt(val component: PasswordPromptComponent) : DialogChild
    }
}
