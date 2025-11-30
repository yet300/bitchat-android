package com.bitchat.android.feature.chat.store

import com.arkivanov.mvikotlin.core.store.Store
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.CommandSuggestion
import com.bitchat.android.ui.GeoPerson

/**
 * MVIKotlin Store for Chat feature
 * Migrated from ChatViewModel MVVM pattern to MVI architecture
 */
internal interface ChatStore : Store<ChatStore.Intent, ChatStore.State, ChatStore.Label> {

    data class State(
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
        
        // Private chat state
        val selectedPrivateChatPeer: String? = null,
        val unreadPrivateMessages: Set<String> = emptySet(),
        
        // Location/Geohash state
        val selectedLocationChannel: ChannelID? = null,
        val isTeleported: Boolean = false,
        val geohashPeople: List<GeoPerson> = emptyList(),
        val teleportedGeo: Set<String> = emptySet(),
        val geohashParticipantCounts: Map<String, Int> = emptyMap(),
        val geohashBookmarks: List<String> = emptyList(),
        val geohashBookmarkNames: Map<String, String> = emptyMap(),
        val locationPermissionState: com.bitchat.android.geohash.LocationChannelManager.PermissionState = com.bitchat.android.geohash.LocationChannelManager.PermissionState.NOT_DETERMINED,
        val locationServicesEnabled: Boolean = false,
        val locationNotes: List<com.bitchat.android.nostr.LocationNotesManager.Note> = emptyList(),
        
        // Peer info
        val peerSessionStates: Map<String, String> = emptyMap(),
        val peerFingerprints: Map<String, String> = emptyMap(),
        val peerNicknames: Map<String, String> = emptyMap(),
        val peerRSSI: Map<String, Int> = emptyMap(),
        val peerDirect: Map<String, Boolean> = emptyMap(),
        val favoritePeers: Set<String> = emptySet(),
        
        // Network state
        val torStatus: com.bitchat.android.net.TorManager.TorStatus = com.bitchat.android.net.TorManager.TorStatus(),
        val powEnabled: Boolean = false,
        val powDifficulty: Int = 20,
        val isMining: Boolean = false,
        
        // UI state
        val showCommandSuggestions: Boolean = false,
        val commandSuggestions: List<CommandSuggestion> = emptyList(),
        val showMentionSuggestions: Boolean = false,
        val mentionSuggestions: List<String> = emptyList(),
        val showPasswordPrompt: Boolean = false,
        val passwordPromptChannel: String? = null,
        
        // Loading states
        val isLoading: Boolean = true,
        val isSendingMessage: Boolean = false
    ) {
        val hasUnreadChannels: Boolean
            get() = unreadChannelMessages.values.any { it > 0 }
        
        val hasUnreadPrivateMessages: Boolean
            get() = unreadPrivateMessages.isNotEmpty()
    }

    sealed class Intent {
        // Message actions
        data class SendMessage(val content: String) : Intent()
        data class SendVoiceNote(val toPeerID: String?, val channel: String?, val filePath: String) : Intent()
        data class SendImageNote(val toPeerID: String?, val channel: String?, val filePath: String) : Intent()
        data class SendFileNote(val toPeerID: String?, val channel: String?, val filePath: String) : Intent()
        data class CancelMediaSend(val messageId: String) : Intent()
        
        // Channel actions
        data class JoinChannel(val channel: String, val password: String? = null) : Intent()
        data class SwitchToChannel(val channel: String?) : Intent()
        data class LeaveChannel(val channel: String) : Intent()
        
        // Private chat actions
        data class StartPrivateChat(val peerID: String) : Intent()
        data object EndPrivateChat : Intent()
        data object OpenLatestUnreadPrivateChat : Intent()
        data class StartGeohashDM(val nostrPubkey: String) : Intent()
        
        // Location channel actions
        data class SelectLocationChannel(val channelID: ChannelID?) : Intent()
        
        // Peer actions
        data class ToggleFavorite(val peerID: String) : Intent()
        data class SetNickname(val nickname: String) : Intent()
        
        // Password prompt
        data class SubmitChannelPassword(val channel: String, val password: String) : Intent()
        data object DismissPasswordPrompt : Intent()
        
        // App lifecycle
        data class SetAppBackgroundState(val isBackground: Boolean) : Intent()
        
        // Notification management
        data class ClearNotificationsForSender(val senderID: String) : Intent()
        data class ClearNotificationsForGeohash(val geohash: String) : Intent()
        
        // Command/Mention suggestions
        data class UpdateCommandSuggestions(val input: String) : Intent()
        data class UpdateMentionSuggestions(val input: String) : Intent()
        data class SelectCommandSuggestion(val suggestion: CommandSuggestion) : Intent()
        data class SelectMentionSuggestion(val nickname: String, val currentText: String) : Intent()
        
        // Geohash actions
        data class TeleportToGeohash(val geohash: String) : Intent()
        data object RefreshLocationChannels : Intent()
        data class ToggleGeohashBookmark(val geohash: String) : Intent()
        data class BlockUserInGeohash(val nickname: String) : Intent()
        
        // Peer management
        data class BlockPeer(val peerID: String) : Intent()
        data class UnblockPeer(val peerID: String) : Intent()
        
        // Location services
        data object EnableLocationChannels : Intent()
        data object EnableLocationServices : Intent()
        data object DisableLocationServices : Intent()
        data class SetTeleported(val teleported: Boolean) : Intent()
        data object BeginLiveRefresh : Intent()
        data object EndLiveRefresh : Intent()
        
        // Debug
        data object GetDebugStatus : Intent()
        
        // Emergency actions
        data object PanicClearAllData : Intent()
    }

    sealed class Action {
        data object Init : Action()
        data object LoadData : Action()
    }

    sealed class Msg {
        // Messages
        data class MessagesUpdated(val messages: List<BitchatMessage>) : Msg()
        data class ChannelMessagesUpdated(val channelMessages: Map<String, List<BitchatMessage>>) : Msg()
        data class PrivateChatsUpdated(val privateChats: Map<String, List<BitchatMessage>>) : Msg()
        data class MessageAdded(val message: BitchatMessage) : Msg()
        
        // Connection
        data class ConnectionStateChanged(val isConnected: Boolean) : Msg()
        data class ConnectedPeersUpdated(val peers: List<String>) : Msg()
        data class MyPeerIDSet(val peerID: String) : Msg()
        data class NicknameChanged(val nickname: String) : Msg()
        
        // Channels
        data class JoinedChannelsUpdated(val channels: Set<String>) : Msg()
        data class CurrentChannelChanged(val channel: String?) : Msg()
        data class PasswordProtectedChannelsUpdated(val channels: Set<String>) : Msg()
        data class UnreadChannelMessagesUpdated(val unread: Map<String, Int>) : Msg()
        
        // Private chats
        data class SelectedPrivateChatPeerChanged(val peerID: String?) : Msg()
        data class UnreadPrivateMessagesUpdated(val unread: Set<String>) : Msg()
        
        // Location
        data class SelectedLocationChannelChanged(val channelID: ChannelID?) : Msg()
        data class TeleportedStateChanged(val isTeleported: Boolean) : Msg()
        data class GeohashPeopleUpdated(val people: List<GeoPerson>) : Msg()
        data class TeleportedGeoUpdated(val geo: Set<String>) : Msg()
        data class GeohashParticipantCountsUpdated(val counts: Map<String, Int>) : Msg()
        data class GeohashBookmarksUpdated(val bookmarks: List<String>) : Msg()
        data class GeohashBookmarkNamesUpdated(val names: Map<String, String>) : Msg()
        
        // Peer info
        data class PeerSessionStatesUpdated(val states: Map<String, String>) : Msg()
        data class PeerFingerprintsUpdated(val fingerprints: Map<String, String>) : Msg()
        data class PeerNicknamesUpdated(val nicknames: Map<String, String>) : Msg()
        data class PeerRSSIUpdated(val rssi: Map<String, Int>) : Msg()
        data class PeerDirectUpdated(val direct: Map<String, Boolean>) : Msg()
        data class FavoritePeersUpdated(val favorites: Set<String>) : Msg()
        
        // Location permissions
        data class LocationPermissionStateChanged(val state: com.bitchat.android.geohash.LocationChannelManager.PermissionState) : Msg()
        data class LocationServicesEnabledChanged(val enabled: Boolean) : Msg()
        
        // Network state
        data class TorStatusChanged(val status: com.bitchat.android.net.TorManager.TorStatus) : Msg()
        data class PoWEnabledChanged(val enabled: Boolean) : Msg()
        data class PoWDifficultyChanged(val difficulty: Int) : Msg()
        data class IsMiningChanged(val isMining: Boolean) : Msg()
        
        // UI state
        data class CommandSuggestionsUpdated(val show: Boolean, val suggestions: List<CommandSuggestion>) : Msg()
        data class MentionSuggestionsUpdated(val show: Boolean, val suggestions: List<String>) : Msg()
        data object CommandSuggestionsCleared : Msg()
        data object MentionSuggestionsCleared : Msg()
        data class PasswordPromptStateChanged(val show: Boolean, val channel: String?) : Msg()
        
        // Loading
        data class LoadingChanged(val isLoading: Boolean) : Msg()
        data class SendingMessageChanged(val isSending: Boolean) : Msg()
    }

    sealed class Label {
        data class ShowError(val message: String) : Label()
        data class MessageSent(val messageId: String) : Label()
        data class ChannelJoined(val channel: String) : Label()
        data class ChannelLeft(val channel: String) : Label()
        data class PrivateChatStarted(val peerID: String) : Label()
        data object PrivateChatEnded : Label()
        data class ShowPasswordPrompt(val channel: String) : Label()
        data class NavigateToPrivateChat(val peerID: String) : Label()
    }
}
