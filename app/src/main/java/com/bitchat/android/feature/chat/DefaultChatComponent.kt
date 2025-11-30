package com.bitchat.android.feature.chat

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.bitchat.android.core.common.asValue
import com.bitchat.android.core.common.coroutineScope
import com.bitchat.android.feature.about.DefaultAboutComponent
import com.bitchat.android.feature.chat.integration.stateToModel
import com.bitchat.android.feature.chat.locationchannels.DefaultLocationChannelsComponent
import com.bitchat.android.feature.chat.locationnotes.DefaultLocationNotesComponent
import com.bitchat.android.feature.chat.meshpeerlist.DefaultMeshPeerListComponent
import com.bitchat.android.feature.chat.passwordprompt.DefaultPasswordPromptComponent
import com.bitchat.android.feature.chat.store.ChatStore
import com.bitchat.android.feature.chat.store.ChatStoreFactory
import com.bitchat.android.feature.chat.usersheet.DefaultUserSheetComponent
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.ui.colorForPeerSeed
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Default implementation of ChatComponent using Decompose + MVIKotlin
 * Migrated from MVVM pattern to MVI architecture
 */
class DefaultChatComponent(
    componentContext: ComponentContext,
    private val startupConfig: ChatComponent.ChatStartupConfig
) : ChatComponent, ComponentContext by componentContext, KoinComponent {

    private val storeFactory: StoreFactory by inject()
    private val meshService: BluetoothMeshService by inject()
    private val favoritesService: FavoritesPersistenceService by inject()

    private val store = instanceKeeper.getStore {
        ChatStoreFactory(storeFactory, startupConfig).create()
    }

    override val model: Value<ChatComponent.Model> = store.asValue().map(stateToModel)

    init {
        // Handle labels from the store
        coroutineScope().launch {
            store.labels.collect { label ->
                when (label) {
                    is ChatStore.Label.ShowError -> {
                        // Handle error - could show a snackbar or dialog
                    }
                    is ChatStore.Label.ShowPasswordPrompt -> {
                        onShowPasswordPrompt(label.channel)
                    }
                    is ChatStore.Label.NavigateToPrivateChat -> {
                        // Navigation handled internally
                    }
                    else -> { /* Other labels */ }
                }
            }
        }
        // Startup config is now handled by ChatStoreFactory.handleStartupConfig()
    }

    private val sheetNavigation = SlotNavigation<SheetConfig>()
    private val dialogNavigation = SlotNavigation<DialogConfig>()

    override val sheetSlot: Value<ChildSlot<*, ChatComponent.SheetChild>> =
        childSlot(
            source = sheetNavigation,
            serializer = SheetConfig.serializer(),
            key = "ChatSheet",
            handleBackButton = true,
            childFactory = ::createSheetChild
        )

    override val dialogSlot: Value<ChildSlot<*, ChatComponent.DialogChild>> =
        childSlot(
            source = dialogNavigation,
            serializer = DialogConfig.serializer(),
            key = "ChatDialog",
            handleBackButton = true,
            childFactory = ::createDialogChild
        )

    override fun onDismissSheet() {
        sheetNavigation.dismiss()
    }

    override fun onShowAppInfo() {
        sheetNavigation.activate(SheetConfig.AppInfo)
    }

    override fun onShowLocationChannels() {
        sheetNavigation.activate(SheetConfig.LocationChannels)
    }

    override fun onShowLocationNotes() {
        sheetNavigation.activate(SheetConfig.LocationNotes)
    }

    override fun onShowUserSheet(nickname: String, messageId: String?) {
        sheetNavigation.activate(SheetConfig.UserSheet(nickname, messageId))
    }

    override fun onShowMeshPeerList() {
        sheetNavigation.activate(SheetConfig.MeshPeerList)
    }

    override fun onShowDebugSettings() {
        sheetNavigation.activate(SheetConfig.DebugSettings)
    }

    override fun onDismissDialog() {
        dialogNavigation.dismiss()
    }

    override fun onShowPasswordPrompt(channelName: String) {
        dialogNavigation.activate(DialogConfig.PasswordPrompt(channelName))
    }

    private fun createSheetChild(
        config: SheetConfig,
        componentContext: ComponentContext
    ): ChatComponent.SheetChild =
        when (config) {
            is SheetConfig.AppInfo -> ChatComponent.SheetChild.AppInfo(
                component = DefaultAboutComponent(
                    componentContext = componentContext,
                    onDismissCallback = ::onDismissSheet
                )
            )
            is SheetConfig.LocationChannels -> ChatComponent.SheetChild.LocationChannels(
                component = DefaultLocationChannelsComponent(
                    componentContext = componentContext,
                    onDismissCallback = ::onDismissSheet
                )
            )
            is SheetConfig.LocationNotes -> ChatComponent.SheetChild.LocationNotes(
                component = DefaultLocationNotesComponent(
                    componentContext = componentContext
                )
            )
            is SheetConfig.UserSheet -> {
                // Get data from Store state instead of ChatViewModel
                val storeState = store.state
                val selectedMessage = config.messageId?.let { messageId ->
                    storeState.messages.find { it.id == messageId }
                }

                ChatComponent.SheetChild.UserSheet(
                    component = DefaultUserSheetComponent(
                        componentContext = componentContext,
                        targetNickname = config.nickname,
                        selectedMessage = selectedMessage,
                        currentNickname = storeState.nickname,
                        isGeohashChannel = storeState.selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location,
                        onDismissCallback = ::onDismissSheet
                    )
                )
            }
            is SheetConfig.MeshPeerList -> ChatComponent.SheetChild.MeshPeerList(
                component = DefaultMeshPeerListComponent(
                    componentContext = componentContext,
                    onDismissCallback = ::onDismissSheet
                )
            )
            is SheetConfig.DebugSettings -> ChatComponent.SheetChild.DebugSettings(
                component = com.bitchat.android.feature.debug.DefaultDebugComponent(
                    componentContext = componentContext,
                    onDismissRequest = ::onDismissSheet
                )
            )
        }

    private fun createDialogChild(
        config: DialogConfig,
        componentContext: ComponentContext
    ): ChatComponent.DialogChild =
        when (config) {
            is DialogConfig.PasswordPrompt -> ChatComponent.DialogChild.PasswordPrompt(
                component = DefaultPasswordPromptComponent(
                    componentContext = componentContext,
                    channelName = config.channelName,
                    onDismissCallback = ::onDismissDialog
                )
            )
        }

    // Message actions - delegate to store
    override fun onSendMessage(content: String) {
        store.accept(ChatStore.Intent.SendMessage(content))
    }

    override fun onSendVoiceNote(toPeerID: String?, channel: String?, filePath: String) {
        store.accept(ChatStore.Intent.SendVoiceNote(toPeerID, channel, filePath))
    }

    override fun onSendImageNote(toPeerID: String?, channel: String?, filePath: String) {
        store.accept(ChatStore.Intent.SendImageNote(toPeerID, channel, filePath))
    }

    override fun onSendFileNote(toPeerID: String?, channel: String?, filePath: String) {
        store.accept(ChatStore.Intent.SendFileNote(toPeerID, channel, filePath))
    }

    override fun onCancelMediaSend(messageId: String) {
        store.accept(ChatStore.Intent.CancelMediaSend(messageId))
    }

    // Channel actions
    override fun onJoinChannel(channel: String, password: String?) {
        store.accept(ChatStore.Intent.JoinChannel(channel, password))
    }

    override fun onSwitchToChannel(channel: String?) {
        store.accept(ChatStore.Intent.SwitchToChannel(channel))
    }

    override fun onLeaveChannel(channel: String) {
        store.accept(ChatStore.Intent.LeaveChannel(channel))
    }

    // Private chat actions
    override fun onStartPrivateChat(peerID: String) {
        store.accept(ChatStore.Intent.StartPrivateChat(peerID))
    }

    override fun onEndPrivateChat() {
        store.accept(ChatStore.Intent.EndPrivateChat)
    }

    override fun onOpenLatestUnreadPrivateChat() {
        store.accept(ChatStore.Intent.OpenLatestUnreadPrivateChat)
    }

    // Location channel actions
    override fun onSelectLocationChannel(channelID: ChannelID?) {
        store.accept(ChatStore.Intent.SelectLocationChannel(channelID))
    }

    // Peer actions
    override fun onToggleFavorite(peerID: String) {
        store.accept(ChatStore.Intent.ToggleFavorite(peerID))
    }

    override fun onSetNickname(nickname: String) {
        store.accept(ChatStore.Intent.SetNickname(nickname))
    }
    
    override fun onStartGeohashDM(nostrPubkey: String) {
        store.accept(ChatStore.Intent.StartGeohashDM(nostrPubkey))
    }
    
    // Peer info queries - use Store state and utility functions directly
    override fun isPersonTeleported(nostrPubkey: String): Boolean {
        return store.state.teleportedGeo.contains(nostrPubkey.lowercase())
    }
    
    override fun colorForNostrPubkey(pubkey: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        val seed = "nostr:${pubkey.lowercase()}"
        return colorForPeerSeed(seed, isDark)
    }
    
    override fun getPeerNoisePublicKeyHex(peerID: String): String? {
        return try {
            meshService.getPeerInfo(peerID)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
        } catch (_: Exception) { null }
    }
    
    override fun getOfflineFavorites(): List<com.bitchat.android.favorites.FavoriteRelationship> {
        return favoritesService.getOurFavorites()
    }
    
    override fun findNostrPubkey(noiseKey: ByteArray): String? {
        return favoritesService.findNostrPubkey(noiseKey)
    }
    
    override fun isPeerDirectConnection(peerID: String): Boolean {
        return try {
            meshService.getPeerInfo(peerID)?.isDirectConnection == true
        } catch (_: Exception) { false }
    }
    
    override fun colorForMeshPeer(peerID: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        val seed = "noise:${peerID.lowercase()}"
        return com.bitchat.android.ui.colorForPeerSeed(seed, isDark)
    }
    
    override fun getFavoriteStatus(peerID: String): com.bitchat.android.favorites.FavoriteRelationship? {
        return favoritesService.getFavoriteStatus(peerID)
    }

    // Password prompt
    override fun onSubmitChannelPassword(channel: String, password: String) {
        store.accept(ChatStore.Intent.SubmitChannelPassword(channel, password))
    }
    
    // App lifecycle
    override fun onSetAppBackgroundState(isBackground: Boolean) {
        store.accept(ChatStore.Intent.SetAppBackgroundState(isBackground))
    }
    
    // Notification management
    override fun onClearNotificationsForSender(senderID: String) {
        store.accept(ChatStore.Intent.ClearNotificationsForSender(senderID))
    }
    
    override fun onClearNotificationsForGeohash(geohash: String) {
        store.accept(ChatStore.Intent.ClearNotificationsForGeohash(geohash))
    }
    
    // Command/Mention suggestions - delegate to ViewModel (returns processed text)
    override fun onUpdateCommandSuggestions(input: String) {
        store.accept(ChatStore.Intent.UpdateCommandSuggestions(input))
    }
    
    override fun onSelectCommandSuggestion(suggestion: com.bitchat.android.ui.CommandSuggestion): String {
        store.accept(ChatStore.Intent.SelectCommandSuggestion(suggestion))
        return "${suggestion.command} "
    }
    
    override fun onUpdateMentionSuggestions(input: String) {
        store.accept(ChatStore.Intent.UpdateMentionSuggestions(input))
    }
    
    override fun onSelectMentionSuggestion(nickname: String, currentText: String): String {
        store.accept(ChatStore.Intent.SelectMentionSuggestion(nickname, currentText))
        // Find the last @ symbol position and replace with mention
        val atIndex = currentText.lastIndexOf('@')
        return if (atIndex == -1) {
            "$currentText@$nickname "
        } else {
            "${currentText.substring(0, atIndex)}@$nickname "
        }
    }
    
    // Geohash actions
    override fun onTeleportToGeohash(geohash: String) {
        store.accept(ChatStore.Intent.TeleportToGeohash(geohash))
    }
    
    override fun onRefreshLocationChannels() {
        store.accept(ChatStore.Intent.RefreshLocationChannels)
    }
    
    override fun onToggleGeohashBookmark(geohash: String) {
        store.accept(ChatStore.Intent.ToggleGeohashBookmark(geohash))
    }
    
    // Emergency actions
    override fun onPanicClearAllData() {
        store.accept(ChatStore.Intent.PanicClearAllData)
    }

    @Serializable
    sealed interface SheetConfig {
        @Serializable
        data object AppInfo : SheetConfig

        @Serializable
        data object LocationChannels : SheetConfig

        @Serializable
        data object LocationNotes : SheetConfig

        @Serializable
        data class UserSheet(
            val nickname: String,
            val messageId: String?
        ) : SheetConfig

        @Serializable
        data object MeshPeerList : SheetConfig

        @Serializable
        data object DebugSettings : SheetConfig
    }

    @Serializable
    sealed interface DialogConfig {
        @Serializable
        data class PasswordPrompt(val channelName: String) : DialogConfig
    }
}
