package com.bitchat.android.feature.chat.store

import android.util.Log
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.bitchat.android.feature.chat.ChatComponent
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshEventBus
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.ui.CommandSuggestion
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.MediaSendingManager
import com.bitchat.android.nostr.GeohashRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class ChatStoreFactory(
    private val storeFactory: StoreFactory,
    private val startupConfig: ChatComponent.ChatStartupConfig
) : KoinComponent {

    private val meshService: BluetoothMeshService by inject()
    private val messageRouter: MessageRouter by inject()
    private val dataManager: DataManager by inject()
    private val geohashBookmarksStore: GeohashBookmarksStore by inject()
    private val locationChannelManager: LocationChannelManager by inject()
    private val fingerprintManager: com.bitchat.android.mesh.PeerFingerprintManager by inject()
    private val favoritesService: com.bitchat.android.favorites.FavoritesPersistenceService by inject()
    private val seenStore: com.bitchat.android.services.SeenMessageStore by inject()
    private val nostrRelayManager: com.bitchat.android.nostr.NostrRelayManager by inject()
    private val nostrTransport: com.bitchat.android.nostr.NostrTransport by inject()
    private val meshEventBus: MeshEventBus by inject()
    private val geohashRepository: GeohashRepository by inject()
    private val torManager: com.bitchat.android.net.TorManager by inject()
    private val powPreferenceManager: com.bitchat.android.nostr.PoWPreferenceManager by inject()
    private val applicationContext: android.content.Context by inject()
    private val mediaSendingManager: MediaSendingManager by inject()

    fun create(): ChatStore =
        object : ChatStore,
            Store<ChatStore.Intent, ChatStore.State, ChatStore.Label> by storeFactory.create(
                name = "ChatStore",
                initialState = ChatStore.State(),
                bootstrapper = SimpleBootstrapper(ChatStore.Action.Init),
                executorFactory = { ExecutorImpl(startupConfig) },
                reducer = ReducerImpl
            ) {}

    private inner class ExecutorImpl(
        private val startupConfig: ChatComponent.ChatStartupConfig
    ) : CoroutineExecutor<ChatStore.Intent, ChatStore.Action, ChatStore.State, ChatStore.Msg, ChatStore.Label>() {

        // Message deduplication tracking
        private val processedUIMessages = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        
        // Channel encryption keys
        private val channelKeys = mutableMapOf<String, javax.crypto.spec.SecretKeySpec>()
        private val channelPasswords = mutableMapOf<String, String>()
        
        // Helper methods for message management
        private fun addMessage(message: com.bitchat.android.model.BitchatMessage) {
            val currentMessages = state().messages.toMutableList()
            currentMessages.add(message)
            dispatch(ChatStore.Msg.MessagesUpdated(currentMessages))
        }
        
        private fun addSystemMessage(text: String) {
            val sys = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = text,
                timestamp = java.util.Date(),
                isRelay = false
            )
            addMessage(sys)
        }
        
        private fun addChannelMessage(channel: String, message: com.bitchat.android.model.BitchatMessage) {
            val currentChannelMessages = state().channelMessages.toMutableMap()
            if (!currentChannelMessages.containsKey(channel)) {
                currentChannelMessages[channel] = mutableListOf()
            }
            
            val channelMessageList = currentChannelMessages[channel]?.toMutableList() ?: mutableListOf()
            channelMessageList.add(message)
            currentChannelMessages[channel] = channelMessageList
            dispatch(ChatStore.Msg.ChannelMessagesUpdated(currentChannelMessages))
            
            // Update unread count if not currently viewing this channel
            val viewingClassicChannel = state().currentChannel == channel
            val viewingGeohashChannel = try {
                if (channel.startsWith("geo:")) {
                    val geo = channel.removePrefix("geo:")
                    val selected = state().selectedLocationChannel
                    selected is com.bitchat.android.geohash.ChannelID.Location && selected.channel.geohash.equals(geo, ignoreCase = true)
                } else false
            } catch (_: Exception) { false }

            if (!viewingClassicChannel && !viewingGeohashChannel) {
                val currentUnread = state().unreadChannelMessages.toMutableMap()
                currentUnread[channel] = (currentUnread[channel] ?: 0) + 1
                dispatch(ChatStore.Msg.UnreadChannelMessagesUpdated(currentUnread))
            }
        }
        
        private fun addPrivateMessage(peerID: String, message: com.bitchat.android.model.BitchatMessage) {
            val currentPrivateChats = state().privateChats.toMutableMap()
            if (!currentPrivateChats.containsKey(peerID)) {
                currentPrivateChats[peerID] = mutableListOf()
            }
            
            val chatMessages = currentPrivateChats[peerID]?.toMutableList() ?: mutableListOf()
            chatMessages.add(message)
            currentPrivateChats[peerID] = chatMessages
            dispatch(ChatStore.Msg.PrivateChatsUpdated(currentPrivateChats))
            
            // Mark as unread if not currently viewing this chat
            if (state().selectedPrivateChatPeer != peerID && message.sender != state().nickname) {
                val currentUnread = state().unreadPrivateMessages.toMutableSet()
                currentUnread.add(peerID)
                dispatch(ChatStore.Msg.UnreadPrivateMessagesUpdated(currentUnread))
            }
        }
        
        private fun parseMentions(content: String, peerNicknames: Set<String>, currentNickname: String?): List<String> {
            val mentionRegex = "@([a-zA-Z0-9_]+)".toRegex()
            val allNicknames = peerNicknames + (currentNickname ?: "")
            
            return mentionRegex.findAll(content)
                .map { it.groupValues[1] }
                .filter { allNicknames.contains(it) }
                .distinct()
                .toList()
        }
        
        // Command processing
        private fun processCommand(command: String): Boolean {
            if (!command.startsWith("/")) return false
            
            val parts = command.split(" ")
            val cmd = parts.first().lowercase()
            
            return when (cmd) {
                "/help", "/?" -> {
                    handleHelpCommand()
                    true
                }
                "/nick" -> {
                    handleNickCommand(parts)
                    true
                }
                "/j", "/join" -> {
                    handleJoinCommand(parts)
                    true
                }
                "/leave", "/part" -> {
                    handleLeaveCommand(parts)
                    true
                }
                "/clear" -> {
                    handleClearCommand()
                    true
                }
                "/m", "/msg" -> {
                    handleMessageCommand(parts)
                    true
                }
                "/w", "/who" -> {
                    handleWhoCommand()
                    true
                }
                "/channels" -> {
                    handleChannelsCommand()
                    true
                }
                "/block" -> {
                    handleBlockCommand(parts)
                    true
                }
                "/unblock" -> {
                    handleUnblockCommand(parts)
                    true
                }
                "/hug" -> {
                    handleActionCommand(parts, "gives", "a warm hug 🫂")
                    true
                }
                "/slap" -> {
                    handleActionCommand(parts, "slaps", "around a bit with a large trout 🐟")
                    true
                }
                else -> false // Unknown command
            }
        }
        
        private fun handleHelpCommand() {
            val helpText = """
                |available commands:
                |  /help - show this help
                |  /nick <name> - set your nickname
                |  /join <channel> - join or create a channel
                |  /leave [channel] - leave current or specified channel
                |  /msg <user> [message] - start private chat
                |  /who - show online peers
                |  /channels - show all channels
                |  /block [user] - block user or list blocked
                |  /unblock <user> - unblock user
                |  /clear - clear messages
                |  /hug <user> - send a hug
                |  /slap <user> - slap with a trout
            """.trimMargin()
            addSystemMessage(helpText)
        }
        
        private fun handleNickCommand(parts: List<String>) {
            if (parts.size > 1) {
                val newNick = parts.drop(1).joinToString(" ")
                dataManager.saveNickname(newNick)
                dispatch(ChatStore.Msg.NicknameChanged(newNick))
                addSystemMessage("nickname changed to $newNick")
            } else {
                val currentNick = state().nickname ?: meshService.myPeerID
                addSystemMessage("current nickname: $currentNick. usage: /nick <name>")
            }
        }
        
        private fun handleJoinCommand(parts: List<String>) {
            if (parts.size > 1) {
                val channelName = parts[1]
                val channel = if (channelName.startsWith("#")) channelName else "#$channelName"
                val password = if (parts.size > 2) parts[2] else null
                
                scope.launch {
                    joinChannel(channel, password)
                }
            } else {
                addSystemMessage("usage: /join <channel>")
            }
        }
        
        private fun handleLeaveCommand(parts: List<String>) {
            val channel = if (parts.size > 1) {
                val channelName = parts[1]
                if (channelName.startsWith("#")) channelName else "#$channelName"
            } else {
                state().currentChannel
            }
            
            if (channel != null) {
                leaveChannel(channel)
                addSystemMessage("left channel $channel")
            } else {
                addSystemMessage("not in a channel. usage: /leave [channel]")
            }
        }
        
        private fun handleClearCommand() {
            // Clear messages based on current context
            val currentChannel = state().currentChannel
            val selectedPeer = state().selectedPrivateChatPeer
            
            when {
                selectedPeer != null -> {
                    // Clear private chat
                    val updatedChats = state().privateChats.toMutableMap()
                    updatedChats[selectedPeer] = emptyList()
                    dispatch(ChatStore.Msg.PrivateChatsUpdated(updatedChats))
                    addSystemMessage("cleared private chat")
                }
                currentChannel != null -> {
                    // Clear channel messages
                    val updatedChannelMessages = state().channelMessages.toMutableMap()
                    updatedChannelMessages[currentChannel] = emptyList()
                    dispatch(ChatStore.Msg.ChannelMessagesUpdated(updatedChannelMessages))
                    addSystemMessage("cleared channel messages")
                }
                else -> {
                    // Clear main timeline
                    dispatch(ChatStore.Msg.MessagesUpdated(emptyList()))
                    addSystemMessage("cleared messages")
                }
            }
        }
        
        private fun handleMessageCommand(parts: List<String>) {
            if (parts.size > 1) {
                val targetName = parts[1].removePrefix("@")
                val peerID = getPeerIDForNickname(targetName)
                
                if (peerID != null) {
                    scope.launch {
                        startPrivateChat(peerID)
                        
                        if (parts.size > 2) {
                            // Send message immediately
                            val messageContent = parts.drop(2).joinToString(" ")
                            sendMessage(messageContent)
                        } else {
                            addSystemMessage("started private chat with $targetName")
                        }
                    }
                } else {
                    addSystemMessage("user '$targetName' not found. they may be offline or using a different nickname.")
                }
            } else {
                addSystemMessage("usage: /msg <nickname> [message]")
            }
        }
        
        private fun handleWhoCommand() {
            val selectedLocationChannel = state().selectedLocationChannel
            
            when (selectedLocationChannel) {
                is com.bitchat.android.geohash.ChannelID.Mesh, null -> {
                    // Mesh channel: show Bluetooth-connected peers
                    val connectedPeers = state().connectedPeers
                    if (connectedPeers.isEmpty()) {
                        addSystemMessage("no users online")
                    } else {
                        val peerList = connectedPeers.joinToString(", ") { peerID ->
                            state().peerNicknames[peerID] ?: peerID
                        }
                        addSystemMessage("online users: $peerList")
                    }
                }
                is com.bitchat.android.geohash.ChannelID.Location -> {
                    // Location channel: show geohash participants
                    val geohashPeople = state().geohashPeople
                    
                    if (geohashPeople.isEmpty()) {
                        addSystemMessage("no users in this location")
                    } else {
                        val peopleList = geohashPeople
                            .joinToString(", ") { it.displayName }
                        addSystemMessage("users in location: $peopleList")
                    }
                }
            }
        }
        
        private fun handleChannelsCommand() {
            val joinedChannels = state().joinedChannels
            if (joinedChannels.isEmpty()) {
                addSystemMessage("no channels joined. use /join <channel> to join a channel.")
            } else {
                val channelList = joinedChannels.sorted().joinToString(", ")
                addSystemMessage("joined channels: $channelList")
            }
        }
        
        private fun getPeerIDForNickname(nickname: String): String? {
            return state().peerNicknames.entries.find { it.value == nickname }?.key
        }
        
        private fun handleBlockCommand(parts: List<String>) {
            if (parts.size > 1) {
                val targetName = parts[1].removePrefix("@")
                val peerID = getPeerIDForNickname(targetName)
                
                if (peerID != null) {
                    scope.launch {
                        blockPeer(peerID)
                        addSystemMessage("blocked user $targetName")
                    }
                } else {
                    addSystemMessage("user '$targetName' not found")
                }
            } else {
                // List blocked users
                val blockedCount = dataManager.blockedUsers.size
                if (blockedCount == 0) {
                    addSystemMessage("no blocked users")
                } else {
                    addSystemMessage("blocked users: $blockedCount fingerprints")
                }
            }
        }
        
        private fun handleUnblockCommand(parts: List<String>) {
            if (parts.size > 1) {
                val targetName = parts[1].removePrefix("@")
                val peerID = getPeerIDForNickname(targetName)
                
                if (peerID != null) {
                    val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
                    if (fingerprint != null && dataManager.isUserBlocked(fingerprint)) {
                        scope.launch {
                            unblockPeer(peerID)
                            addSystemMessage("unblocked user $targetName")
                        }
                    } else {
                        addSystemMessage("user '$targetName' is not blocked")
                    }
                } else {
                    addSystemMessage("user '$targetName' not found")
                }
            } else {
                addSystemMessage("usage: /unblock <nickname>")
            }
        }
        
        private fun handleActionCommand(parts: List<String>, verb: String, action: String) {
            if (parts.size > 1) {
                val targetName = parts[1].removePrefix("@")
                val peerID = getPeerIDForNickname(targetName)
                
                if (peerID != null) {
                    val senderNickname = state().nickname ?: meshService.myPeerID
                    val actionMessage = "$senderNickname $verb $targetName $action"
                    val mentions = listOf(targetName)
                    
                    val currentChannel = state().currentChannel
                    val selectedPeer = state().selectedPrivateChatPeer
                    
                    // Send action message to appropriate context
                    if (selectedPeer != null) {
                        // Private chat action
                        val message = com.bitchat.android.model.BitchatMessage(
                            sender = senderNickname,
                            content = actionMessage,
                            timestamp = java.util.Date(),
                            isRelay = false,
                            isPrivate = true,
                            senderPeerID = meshService.myPeerID,
                            deliveryStatus = com.bitchat.android.model.DeliveryStatus.Sending
                        )
                        addPrivateMessage(selectedPeer, message)
                        messageRouter.sendPrivate(actionMessage, selectedPeer, "", message.id)
                    } else if (currentChannel != null) {
                        // Channel action
                        meshService.sendMessage(actionMessage, mentions, currentChannel)
                    } else {
                        // Public action
                        meshService.sendMessage(actionMessage, mentions, null)
                    }
                } else {
                    addSystemMessage("user '$targetName' not found")
                }
            } else {
                addSystemMessage("usage: ${parts[0]} <nickname>")
            }
        }

        override fun executeAction(action: ChatStore.Action) {
            when (action) {
                ChatStore.Action.Init -> initialize()
                ChatStore.Action.LoadData -> loadData()
            }
        }

        private fun initialize() {
            scope.launch {
                try {
                    dispatch(ChatStore.Msg.LoadingChanged(true))
                    
                    dispatch(ChatStore.Msg.MyPeerIDSet(meshService.myPeerID))
                    
                    // Wire up MeshEventBus callbacks
                    setupMeshEventBusCallbacks()
                    
                    // Wire up MediaSendingManager callbacks
                    setupMediaSendingManagerCallbacks()
                    
                    // Subscribe to MeshEventBus for mesh events (MVI pattern)
                    subscribeToMeshEvents()
                    
                    // Load persisted data directly
                    loadData()
                    
                    // Subscribe to service flows and ChatViewModel (bridge pattern for remaining state)
                    subscribeToViewModelFlows()
                    
                    // Handle startup config
                    handleStartupConfig()
                    
                    dispatch(ChatStore.Msg.LoadingChanged(false))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize chat", e)
                    dispatch(ChatStore.Msg.LoadingChanged(false))
                    publish(ChatStore.Label.ShowError(e.message ?: "Failed to initialize chat"))
                }
            }
        }
        
        private fun setupMeshEventBusCallbacks() {
            // Provide nickname from Store state
            meshEventBus.nicknameProvider = { state().nickname }
            
            // Provide favorite check from Store state
            meshEventBus.favoriteChecker = { peerID ->
                val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
                fingerprint != null && state().favoritePeers.contains(fingerprint)
            }
            
            // Channel decryption callback
            meshEventBus.channelDecryptor = { encryptedContent, channel ->
                channelKeys[channel]?.let { key ->
                    try {
                        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                        val iv = encryptedContent.take(12).toByteArray()
                        val ciphertext = encryptedContent.drop(12).toByteArray()
                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
                        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decrypt channel message", e)
                        null
                    }
                }
            }
        }
        
        private fun setupMediaSendingManagerCallbacks() {
            mediaSendingManager.nicknameProvider = { state().nickname }
            
            mediaSendingManager.addMessageCallback = { message ->
                addMessage(message)
            }
            
            mediaSendingManager.addPrivateMessageCallback = { peerID, message ->
                addPrivateMessage(peerID, message)
            }
            
            mediaSendingManager.addChannelMessageCallback = { channel, message ->
                addChannelMessage(channel, message)
            }
            
            mediaSendingManager.updateDeliveryStatusCallback = { messageId, status ->
                updateMessageDeliveryStatus(messageId, status)
            }
            
            mediaSendingManager.removeMessageCallback = { messageId ->
                removeMessageById(messageId)
            }
            
            mediaSendingManager.findMessagePathCallback = { messageId ->
                findMessagePathById(messageId)
            }
        }
        
        private fun findMessagePathById(messageId: String): String? {
            // Search main timeline
            state().messages.firstOrNull { it.id == messageId }?.content?.let { return it }
            // Search private chats
            state().privateChats.values.forEach { list ->
                list.firstOrNull { it.id == messageId }?.content?.let { return it }
            }
            // Search channel messages
            state().channelMessages.values.forEach { list ->
                list.firstOrNull { it.id == messageId }?.content?.let { return it }
            }
            return null
        }
        
        private fun removeMessageById(messageId: String) {
            // Remove from main timeline
            val updatedMessages = state().messages.filter { it.id != messageId }
            if (updatedMessages.size != state().messages.size) {
                dispatch(ChatStore.Msg.MessagesUpdated(updatedMessages))
                return
            }
            
            // Remove from private chats
            val updatedPrivateChats = state().privateChats.mapValues { (_, messages) ->
                messages.filter { it.id != messageId }
            }
            dispatch(ChatStore.Msg.PrivateChatsUpdated(updatedPrivateChats))
            
            // Remove from channel messages
            val updatedChannelMessages = state().channelMessages.mapValues { (_, messages) ->
                messages.filter { it.id != messageId }
            }
            dispatch(ChatStore.Msg.ChannelMessagesUpdated(updatedChannelMessages))
        }
        
        private fun subscribeToMeshEvents() {
            // Subscribe to incoming messages from mesh
            scope.launch {
                meshEventBus.messageReceived.collect { message ->
                    handleIncomingMessage(message)
                }
            }
            
            // Subscribe to delivery acknowledgments
            scope.launch {
                meshEventBus.deliveryAck.collect { event ->
                    updateMessageDeliveryStatus(
                        event.messageID, 
                        com.bitchat.android.model.DeliveryStatus.Delivered(event.recipientPeerID, java.util.Date())
                    )
                }
            }
            
            // Subscribe to read receipts
            scope.launch {
                meshEventBus.readReceipt.collect { event ->
                    updateMessageDeliveryStatus(
                        event.messageID, 
                        com.bitchat.android.model.DeliveryStatus.Read(event.recipientPeerID, java.util.Date())
                    )
                }
            }
            
            // Subscribe to channel leave events
            scope.launch {
                meshEventBus.channelLeave.collect { event ->
                    // Handle channel leave - could add system message
                    Log.d(TAG, "Peer ${event.fromPeer} left channel ${event.channel}")
                }
            }
        }
        
        private fun handleIncomingMessage(message: BitchatMessage) {
            // Determine where to add the message based on its properties
            when {
                message.isPrivate -> {
                    val peerID = message.senderPeerID ?: message.sender
                    addPrivateMessage(peerID, message)
                }
                message.channel != null -> {
                    addChannelMessage(message.channel!!, message)
                }
                else -> {
                    addMessage(message)
                }
            }
        }
        
        private fun updateMessageDeliveryStatus(messageID: String, status: com.bitchat.android.model.DeliveryStatus) {
            // Update in private chats
            val updatedPrivateChats = state().privateChats.toMutableMap()
            var updated = false
            
            updatedPrivateChats.forEach { (peerID, messages) ->
                val updatedMessages = messages.toMutableList()
                val messageIndex = updatedMessages.indexOfFirst { it.id == messageID }
                if (messageIndex >= 0) {
                    updatedMessages[messageIndex] = updatedMessages[messageIndex].copy(deliveryStatus = status)
                    updatedPrivateChats[peerID] = updatedMessages
                    updated = true
                }
            }
            
            if (updated) {
                dispatch(ChatStore.Msg.PrivateChatsUpdated(updatedPrivateChats))
            }
        }

        private fun loadData() {
            scope.launch {
                try {
                    // Load nickname from persistence
                    val nickname = dataManager.loadNickname()
                    dispatch(ChatStore.Msg.NicknameChanged(nickname))
                    
                    // Load channel data
                    val (joinedChannels, protectedChannels) = dataManager.loadChannelData()
                    dispatch(ChatStore.Msg.JoinedChannelsUpdated(joinedChannels))
                    dispatch(ChatStore.Msg.PasswordProtectedChannelsUpdated(protectedChannels))
                    
                    // Load favorites
                    dataManager.loadFavorites()
                    dispatch(ChatStore.Msg.FavoritePeersUpdated(dataManager.favoritePeers))
                    
                    // Load blocked users
                    dataManager.loadBlockedUsers()
                    dataManager.loadGeohashBlockedUsers()
                    
                    Log.d(TAG, "Loaded persisted data: nickname=$nickname, channels=${joinedChannels.size}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load persisted data", e)
                }
            }
        }

        /**
         * Subscribe to service flows and ChatViewModel flows (bridge pattern for gradual migration)
         */
        private fun subscribeToViewModelFlows() {
            // === MIGRATED: Using direct services ===
            
            // Connected peers from MeshEventBus
            scope.launch {
                meshEventBus.connectedPeers.collectLatest { peers ->
                    dispatch(ChatStore.Msg.ConnectedPeersUpdated(peers))
                    dispatch(ChatStore.Msg.ConnectionStateChanged(peers.isNotEmpty()))
                }
            }
            
            // Location channel from LocationChannelManager
            scope.launch {
                locationChannelManager.selectedChannel.collectLatest { channel ->
                    dispatch(ChatStore.Msg.SelectedLocationChannelChanged(channel))
                }
            }
            
            // Teleported state from LocationChannelManager
            scope.launch {
                locationChannelManager.teleported.collectLatest { teleported ->
                    dispatch(ChatStore.Msg.TeleportedStateChanged(teleported))
                }
            }
            
            // Location permissions from LocationChannelManager
            scope.launch {
                locationChannelManager.permissionState.collectLatest { state ->
                    dispatch(ChatStore.Msg.LocationPermissionStateChanged(state))
                }
            }
            scope.launch {
                locationChannelManager.locationServicesEnabled.collectLatest { enabled ->
                    dispatch(ChatStore.Msg.LocationServicesEnabledChanged(enabled))
                }
            }
            
            // Geohash bookmarks from GeohashBookmarksStore
            scope.launch {
                geohashBookmarksStore.bookmarks.collectLatest { bookmarks ->
                    dispatch(ChatStore.Msg.GeohashBookmarksUpdated(bookmarks))
                }
            }
            scope.launch {
                geohashBookmarksStore.bookmarkNames.collectLatest { names ->
                    dispatch(ChatStore.Msg.GeohashBookmarkNamesUpdated(names))
                }
            }
            
            // Geohash people from GeohashRepository (direct service access)
            scope.launch {
                geohashRepository.geohashPeople.collectLatest { people ->
                    dispatch(ChatStore.Msg.GeohashPeopleUpdated(people))
                }
            }
            
            // Geohash participant counts from GeohashRepository (direct service access)
            scope.launch {
                geohashRepository.geohashParticipantCounts.collectLatest { counts ->
                    dispatch(ChatStore.Msg.GeohashParticipantCountsUpdated(counts))
                }
            }
            
            // === State now managed by Store directly ===
            // Messages: handled by MeshEventBus.messageReceived -> handleIncomingMessage()
            // Joined channels: managed by joinChannel()/leaveChannel() in Store
            // Current channel: managed by switchToChannel() in Store
            // Channel messages: managed by addChannelMessage() in Store
            // Private chats: managed by startPrivateChat()/addPrivateMessage() in Store
            // Selected private chat peer: managed by startPrivateChat()/endPrivateChat() in Store
            // Unread counts: managed by Store when adding messages
            
            // Initialize nickname from DataManager
            scope.launch {
                val savedNickname = dataManager.loadNickname()
                dispatch(ChatStore.Msg.NicknameChanged(savedNickname))
            }
            
            // Initialize joined channels from DataManager
            scope.launch {
                val (channels, passwordProtected) = dataManager.loadChannelData()
                dispatch(ChatStore.Msg.JoinedChannelsUpdated(channels))
                dispatch(ChatStore.Msg.PasswordProtectedChannelsUpdated(passwordProtected))
            }
            
            // Initialize favorite peers from DataManager
            scope.launch {
                dataManager.loadFavorites()
                dispatch(ChatStore.Msg.FavoritePeersUpdated(dataManager.favoritePeers))
            }
            
            // Peer info polling - get from BluetoothMeshService periodically
            scope.launch {
                while (true) {
                    try {
                        // Get peer nicknames
                        val nicknames = meshService.getPeerNicknames()
                        dispatch(ChatStore.Msg.PeerNicknamesUpdated(nicknames))
                        
                        // Get RSSI values
                        val rssi = meshService.getPeerRSSI()
                        dispatch(ChatStore.Msg.PeerRSSIUpdated(rssi))
                        
                        // Get session states and fingerprints for each connected peer
                        val connectedPeers = state().connectedPeers
                        val sessionStates = mutableMapOf<String, String>()
                        val fingerprints = mutableMapOf<String, String>()
                        val directConnections = mutableMapOf<String, Boolean>()
                        
                        connectedPeers.forEach { peerID ->
                            sessionStates[peerID] = meshService.getSessionState(peerID).toString()
                            meshService.getPeerFingerprint(peerID)?.let { fp ->
                                fingerprints[peerID] = fp
                            }
                            // Direct connection status from connection manager
                            directConnections[peerID] = meshService.getDeviceAddressForPeer(peerID) != null
                        }
                        
                        dispatch(ChatStore.Msg.PeerSessionStatesUpdated(sessionStates))
                        dispatch(ChatStore.Msg.PeerFingerprintsUpdated(fingerprints))
                        dispatch(ChatStore.Msg.PeerDirectUpdated(directConnections))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get peer info", e)
                    }
                    kotlinx.coroutines.delay(2000)
                }
            }
            
            // Command/mention suggestions - managed by Store directly via updateCommandSuggestions/updateMentionSuggestions
            // Password prompt state - managed by Store directly via joinChannel()
            
            // Network state - using services directly
            scope.launch {
                torManager.statusFlow.collectLatest { status ->
                    dispatch(ChatStore.Msg.TorStatusChanged(status))
                }
            }
            scope.launch {
                powPreferenceManager.powEnabled.collectLatest { enabled ->
                    dispatch(ChatStore.Msg.PoWEnabledChanged(enabled))
                }
            }
            scope.launch {
                powPreferenceManager.powDifficulty.collectLatest { difficulty ->
                    dispatch(ChatStore.Msg.PoWDifficultyChanged(difficulty))
                }
            }
            scope.launch {
                powPreferenceManager.isMining.collectLatest { isMining ->
                    dispatch(ChatStore.Msg.IsMiningChanged(isMining))
                }
            }
        }

        private fun handleStartupConfig() {
            when (startupConfig) {
                is ChatComponent.ChatStartupConfig.PrivateChat -> {
                    scope.launch {
                        // Start private chat with the specified peer - use Store method directly
                        startPrivateChat(startupConfig.peerId)
                        // TODO: Implement notification clearing when notification service is available
                    }
                }
                is ChatComponent.ChatStartupConfig.GeohashChat -> {
                    scope.launch {
                        // Navigate to geohash channel
                        val geohash = startupConfig.geohash
                        val level = when (geohash.length) {
                            7 -> com.bitchat.android.geohash.GeohashChannelLevel.BLOCK
                            6 -> com.bitchat.android.geohash.GeohashChannelLevel.NEIGHBORHOOD
                            5 -> com.bitchat.android.geohash.GeohashChannelLevel.CITY
                            4 -> com.bitchat.android.geohash.GeohashChannelLevel.PROVINCE
                            2 -> com.bitchat.android.geohash.GeohashChannelLevel.REGION
                            else -> com.bitchat.android.geohash.GeohashChannelLevel.CITY
                        }
                        val geohashChannel = com.bitchat.android.geohash.GeohashChannel(level, geohash)
                        val channelID = com.bitchat.android.geohash.ChannelID.Location(geohashChannel)
                        // Use LocationChannelManager which handles geohash switching internally
                        locationChannelManager.select(channelID)
                        // TODO: Implement notification clearing when notification service is available
                    }
                }
                ChatComponent.ChatStartupConfig.Default -> {
                    // Default startup - show mesh chat
                }
            }
        }

        override fun executeIntent(intent: ChatStore.Intent) {
            when (intent) {
                is ChatStore.Intent.SendMessage -> sendMessage(intent.content)
                is ChatStore.Intent.SendVoiceNote -> sendVoiceNote(intent.toPeerID, intent.channel, intent.filePath)
                is ChatStore.Intent.SendImageNote -> sendImageNote(intent.toPeerID, intent.channel, intent.filePath)
                is ChatStore.Intent.SendFileNote -> sendFileNote(intent.toPeerID, intent.channel, intent.filePath)
                is ChatStore.Intent.CancelMediaSend -> cancelMediaSend(intent.messageId)
                
                is ChatStore.Intent.JoinChannel -> joinChannel(intent.channel, intent.password)
                is ChatStore.Intent.SwitchToChannel -> switchToChannel(intent.channel)
                is ChatStore.Intent.LeaveChannel -> leaveChannel(intent.channel)
                
                is ChatStore.Intent.StartPrivateChat -> startPrivateChat(intent.peerID)
                ChatStore.Intent.EndPrivateChat -> endPrivateChat()
                ChatStore.Intent.OpenLatestUnreadPrivateChat -> openLatestUnreadPrivateChat()
                is ChatStore.Intent.StartGeohashDM -> startGeohashDM(intent.nostrPubkey)
                
                is ChatStore.Intent.SelectLocationChannel -> selectLocationChannel(intent.channelID)
                
                is ChatStore.Intent.ToggleFavorite -> toggleFavorite(intent.peerID)
                is ChatStore.Intent.SetNickname -> setNickname(intent.nickname)
                
                is ChatStore.Intent.SubmitChannelPassword -> submitChannelPassword(intent.channel, intent.password)
                ChatStore.Intent.DismissPasswordPrompt -> dismissPasswordPrompt()
                
                is ChatStore.Intent.SetAppBackgroundState -> setAppBackgroundState(intent.isBackground)
                is ChatStore.Intent.ClearNotificationsForSender -> clearNotificationsForSender(intent.senderID)
                is ChatStore.Intent.ClearNotificationsForGeohash -> clearNotificationsForGeohash(intent.geohash)
                
                is ChatStore.Intent.UpdateCommandSuggestions -> updateCommandSuggestions(intent.input)
                is ChatStore.Intent.UpdateMentionSuggestions -> updateMentionSuggestions(intent.input)
                
                is ChatStore.Intent.TeleportToGeohash -> teleportToGeohash(intent.geohash)
                ChatStore.Intent.RefreshLocationChannels -> refreshLocationChannels()
                is ChatStore.Intent.ToggleGeohashBookmark -> toggleGeohashBookmark(intent.geohash)
                is ChatStore.Intent.BlockUserInGeohash -> blockUserInGeohash(intent.nickname)
                
                is ChatStore.Intent.BlockPeer -> blockPeer(intent.peerID)
                is ChatStore.Intent.UnblockPeer -> unblockPeer(intent.peerID)
                
                is ChatStore.Intent.SelectCommandSuggestion -> selectCommandSuggestion(intent.suggestion)
                is ChatStore.Intent.SelectMentionSuggestion -> selectMentionSuggestion(intent.nickname, intent.currentText)
                
                ChatStore.Intent.EnableLocationChannels -> enableLocationChannels()
                ChatStore.Intent.EnableLocationServices -> enableLocationServices()
                ChatStore.Intent.DisableLocationServices -> disableLocationServices()
                is ChatStore.Intent.SetTeleported -> setTeleported(intent.teleported)
                ChatStore.Intent.BeginLiveRefresh -> beginLiveRefresh()
                ChatStore.Intent.EndLiveRefresh -> endLiveRefresh()
                
                ChatStore.Intent.GetDebugStatus -> getDebugStatus()
                
                ChatStore.Intent.PanicClearAllData -> panicClearAllData()
            }
        }

        // Message actions - now with more direct logic
        private fun sendMessage(content: String) {
            if (content.isEmpty()) return
            
            scope.launch {
                try {
                    dispatch(ChatStore.Msg.SendingMessageChanged(true))
                    
                    // Check for commands
                    if (content.startsWith("/")) {
                        val handled = processCommand(content)
                        if (!handled) {
                            // Unknown command - show help
                            addSystemMessage("unknown command. type /help for available commands")
                        }
                        dispatch(ChatStore.Msg.SendingMessageChanged(false))
                        return@launch
                    }
                    
                    val mentions = parseMentions(content, meshService.getPeerNicknames().values.toSet(), state().nickname)
                    val selectedPeer = state().selectedPrivateChatPeer
                    val currentChannel = state().currentChannel
                    
                    if (selectedPeer != null) {
                        // Send private message
                        val recipientNickname = meshService.getPeerNicknames()[selectedPeer]
                        val message = com.bitchat.android.model.BitchatMessage(
                            sender = state().nickname ?: meshService.myPeerID,
                            content = content,
                            timestamp = java.util.Date(),
                            isRelay = false,
                            isPrivate = true,
                            recipientNickname = recipientNickname,
                            senderPeerID = meshService.myPeerID,
                            deliveryStatus = com.bitchat.android.model.DeliveryStatus.Sending
                        )
                        
                        addPrivateMessage(selectedPeer, message)
                        
                        // Route via MessageRouter (mesh when connected+established, else Nostr)
                        messageRouter.sendPrivate(content, selectedPeer, recipientNickname ?: "", message.id)
                        
                        dispatch(ChatStore.Msg.SendingMessageChanged(false))
                        publish(ChatStore.Label.MessageSent(message.id))
                    } else {
                        // Check if we're in a location channel
                        val selectedLocationChannel = state().selectedLocationChannel
                        if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                            // Send to geohash channel directly using services
                            sendGeohashMessage(content, selectedLocationChannel.channel)
                        } else {
                            // Send public/channel message via mesh
                            val message = com.bitchat.android.model.BitchatMessage(
                                sender = state().nickname ?: meshService.myPeerID,
                                content = content,
                                timestamp = java.util.Date(),
                                isRelay = false,
                                senderPeerID = meshService.myPeerID,
                                mentions = if (mentions.isNotEmpty()) mentions else null,
                                channel = currentChannel
                            )

                            if (currentChannel != null) {
                                addChannelMessage(currentChannel, message)
                                meshService.sendMessage(content, mentions, currentChannel)
                            } else {
                                addMessage(message)
                                meshService.sendMessage(content, mentions, null)
                            }
                        }
                        
                        dispatch(ChatStore.Msg.SendingMessageChanged(false))
                        publish(ChatStore.Label.MessageSent(java.util.UUID.randomUUID().toString()))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send message", e)
                    dispatch(ChatStore.Msg.SendingMessageChanged(false))
                    publish(ChatStore.Label.ShowError(e.message ?: "Failed to send message"))
                }
            }
        }

        private fun sendVoiceNote(toPeerID: String?, channel: String?, filePath: String) {
            mediaSendingManager.sendVoiceNote(toPeerID, channel, filePath)
        }

        private fun sendImageNote(toPeerID: String?, channel: String?, filePath: String) {
            mediaSendingManager.sendImageNote(toPeerID, channel, filePath)
        }

        private fun sendFileNote(toPeerID: String?, channel: String?, filePath: String) {
            mediaSendingManager.sendFileNote(toPeerID, channel, filePath)
        }

        private fun cancelMediaSend(messageId: String) {
            mediaSendingManager.cancelMediaSend(messageId)
        }

        // Geohash message sending - direct implementation without GeohashViewModel
        private fun sendGeohashMessage(content: String, channel: com.bitchat.android.geohash.GeohashChannel) {
            scope.launch {
                try {
                    val tempId = "temp_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000)}"
                    val pow = powPreferenceManager.getCurrentSettings()
                    val nickname = state().nickname
                    val localMsg = com.bitchat.android.model.BitchatMessage(
                        id = tempId,
                        sender = nickname ?: meshService.myPeerID,
                        content = content,
                        timestamp = java.util.Date(),
                        isRelay = false,
                        senderPeerID = "geohash:${channel.geohash}",
                        channel = "#${channel.geohash}",
                        powDifficulty = if (pow.enabled) pow.difficulty else null
                    )
                    addChannelMessage("geo:${channel.geohash}", localMsg)
                    
                    val startedMining = pow.enabled && pow.difficulty > 0
                    if (startedMining) {
                        com.bitchat.android.ui.PoWMiningTracker.startMiningMessage(tempId)
                    }
                    try {
                        val identity = com.bitchat.android.nostr.NostrIdentityBridge.deriveIdentity(
                            forGeohash = channel.geohash, 
                            context = applicationContext
                        )
                        val teleported = state().isTeleported
                        val event = com.bitchat.android.nostr.NostrProtocol.createEphemeralGeohashEvent(
                            content, channel.geohash, identity, nickname, teleported, powPreferenceManager
                        )
                        nostrRelayManager.sendEventToGeohash(event, channel.geohash, includeDefaults = false, nRelays = 5)
                    } finally {
                        if (startedMining) {
                            com.bitchat.android.ui.PoWMiningTracker.stopMiningMessage(tempId)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send geohash message: ${e.message}")
                }
            }
        }

        // Channel actions - now using services directly
        private fun joinChannel(channel: String, password: String?) {
            scope.launch {
                try {
                    val channelTag = if (channel.startsWith("#")) channel else "#$channel"
                    
                    // Check if already joined
                    if (state().joinedChannels.contains(channelTag)) {
                        if (state().passwordProtectedChannels.contains(channelTag) && !channelKeys.containsKey(channelTag)) {
                            // Need password verification
                            if (password != null) {
                                // TODO: Implement password verification
                                // For now, just accept it
                                channelPasswords[channelTag] = password
                                channelKeys[channelTag] = deriveChannelKey(password, channelTag)
                            } else {
                                dispatch(ChatStore.Msg.PasswordPromptStateChanged(true, channelTag))
                                publish(ChatStore.Label.ShowPasswordPrompt(channelTag))
                                return@launch
                            }
                        }
                        switchToChannel(channelTag)
                        publish(ChatStore.Label.ChannelJoined(channelTag))
                        return@launch
                    }
                    
                    // If password protected and no key yet
                    if (state().passwordProtectedChannels.contains(channelTag) && !channelKeys.containsKey(channelTag)) {
                        if (dataManager.isChannelCreator(channelTag, meshService.myPeerID)) {
                            // Channel creator bypass
                        } else if (password != null) {
                            // TODO: Implement password verification
                            channelPasswords[channelTag] = password
                            channelKeys[channelTag] = deriveChannelKey(password, channelTag)
                        } else {
                            dispatch(ChatStore.Msg.PasswordPromptStateChanged(true, channelTag))
                            publish(ChatStore.Label.ShowPasswordPrompt(channelTag))
                            return@launch
                        }
                    }
                    
                    // Join the channel
                    val updatedChannels = state().joinedChannels.toMutableSet()
                    updatedChannels.add(channelTag)
                    dispatch(ChatStore.Msg.JoinedChannelsUpdated(updatedChannels))
                    
                    // Set as creator if new channel
                    if (!dataManager.channelCreators.containsKey(channelTag) && !state().passwordProtectedChannels.contains(channelTag)) {
                        dataManager.addChannelCreator(channelTag, meshService.myPeerID)
                    }
                    
                    // Add ourselves as member
                    dataManager.addChannelMember(channelTag, meshService.myPeerID)
                    
                    // Initialize channel messages if needed
                    if (!state().channelMessages.containsKey(channelTag)) {
                        val updatedChannelMessages = state().channelMessages.toMutableMap()
                        updatedChannelMessages[channelTag] = emptyList()
                        dispatch(ChatStore.Msg.ChannelMessagesUpdated(updatedChannelMessages))
                    }
                    
                    switchToChannel(channelTag)
                    
                    // Save to persistence
                    dataManager.saveChannelData(state().joinedChannels, state().passwordProtectedChannels)
                    
                    publish(ChatStore.Label.ChannelJoined(channelTag))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to join channel", e)
                    publish(ChatStore.Label.ShowError(e.message ?: "Failed to join channel"))
                }
            }
        }
        
        private fun deriveChannelKey(password: String, channelName: String): javax.crypto.spec.SecretKeySpec {
            // PBKDF2 key derivation (same as iOS version)
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = javax.crypto.spec.PBEKeySpec(
                password.toCharArray(),
                channelName.toByteArray(),
                100000, // 100,000 iterations (same as iOS)
                256 // 256-bit key
            )
            val secretKey = factory.generateSecret(spec)
            return javax.crypto.spec.SecretKeySpec(secretKey.encoded, "AES")
        }

        private fun switchToChannel(channel: String?) {
            // Update store state directly
            dispatch(ChatStore.Msg.CurrentChannelChanged(channel))
            dispatch(ChatStore.Msg.SelectedPrivateChatPeerChanged(null))
            
            // Clear unread count for the channel
            if (channel != null) {
                val currentUnread = state().unreadChannelMessages.toMutableMap()
                currentUnread[channel] = 0
                dispatch(ChatStore.Msg.UnreadChannelMessagesUpdated(currentUnread))
            }
            
            // Store is now the source of truth - no ChatViewModel sync needed
        }

        private fun leaveChannel(channel: String) {
            // Update store state
            val updatedChannels = state().joinedChannels.toMutableSet()
            updatedChannels.remove(channel)
            dispatch(ChatStore.Msg.JoinedChannelsUpdated(updatedChannels))
            
            // Clear current channel if leaving it
            if (state().currentChannel == channel) {
                dispatch(ChatStore.Msg.CurrentChannelChanged(null))
            }
            
            // Remove channel messages
            val updatedMessages = state().channelMessages.toMutableMap()
            updatedMessages.remove(channel)
            dispatch(ChatStore.Msg.ChannelMessagesUpdated(updatedMessages))
            
            // Save to persistence
            dataManager.saveChannelData(updatedChannels, state().passwordProtectedChannels)
            
            // Store is now the source of truth - no ChatViewModel sync needed
            publish(ChatStore.Label.ChannelLeft(channel))
        }

        // Private chat actions - now using services directly
        private fun startPrivateChat(peerID: String) {
            scope.launch {
                try {
                    // Check if peer is blocked
                    val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
                    if (fingerprint != null && dataManager.isUserBlocked(fingerprint)) {
                        val peerNickname = meshService.getPeerNicknames()[peerID] ?: peerID
                        publish(ChatStore.Label.ShowError("Cannot start chat with $peerNickname: user is blocked"))
                        return@launch
                    }
                    
                    // Establish Noise session if needed
                    if (!meshService.hasEstablishedSession(peerID)) {
                        val myPeerID = meshService.myPeerID
                        if (myPeerID < peerID) {
                            meshService.initiateNoiseHandshake(peerID)
                        } else {
                            meshService.sendAnnouncementToPeer(peerID)
                            meshService.initiateNoiseHandshake(peerID)
                        }
                    }
                    
                    // Update store state
                    dispatch(ChatStore.Msg.SelectedPrivateChatPeerChanged(peerID))
                    dispatch(ChatStore.Msg.CurrentChannelChanged(null))
                    
                    // Clear unread for this peer
                    val currentUnread = state().unreadPrivateMessages.toMutableSet()
                    currentUnread.remove(peerID)
                    dispatch(ChatStore.Msg.UnreadPrivateMessagesUpdated(currentUnread))
                    
                    // Initialize chat if needed
                    if (!state().privateChats.containsKey(peerID)) {
                        val updatedChats = state().privateChats.toMutableMap()
                        updatedChats[peerID] = emptyList()
                        dispatch(ChatStore.Msg.PrivateChatsUpdated(updatedChats))
                    }
                    
                    // Store is now the source of truth - no ChatViewModel sync needed
                    publish(ChatStore.Label.PrivateChatStarted(peerID))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start private chat", e)
                    publish(ChatStore.Label.ShowError(e.message ?: "Failed to start private chat"))
                }
            }
        }

        private fun endPrivateChat() {
            // Update store state
            dispatch(ChatStore.Msg.SelectedPrivateChatPeerChanged(null))
            
            // Store is now the source of truth - no ChatViewModel sync needed
            publish(ChatStore.Label.PrivateChatEnded)
        }

        private fun openLatestUnreadPrivateChat() {
            // Find the first peer with unread messages
            val unreadPeers = state().unreadPrivateMessages
            if (unreadPeers.isEmpty()) return
            
            // Get the first unread peer (Set doesn't have ordering, so just pick first)
            val peerID = unreadPeers.firstOrNull() ?: return
            startPrivateChat(peerID)
        }
        
        private fun startGeohashDM(nostrPubkey: String) {
            // Direct implementation using GeohashRepository
            val convKey = "nostr_${nostrPubkey.take(16)}"
            geohashRepository.putNostrKeyMapping(convKey, nostrPubkey)
            
            // Record the conversation's geohash using the currently selected location channel
            val current = state().selectedLocationChannel
            val gh = (current as? com.bitchat.android.geohash.ChannelID.Location)?.channel?.geohash
            if (!gh.isNullOrEmpty()) {
                geohashRepository.setConversationGeohash(convKey, gh)
                com.bitchat.android.nostr.GeohashConversationRegistry.set(convKey, gh)
            }
            
            startPrivateChat(convKey)
            Log.d(TAG, "🗨️ Started geohash DM with $nostrPubkey -> $convKey (geohash=$gh)")
        }

        // Location channel actions
        private fun selectLocationChannel(channelID: com.bitchat.android.geohash.ChannelID?) {
            val effectiveChannelID = channelID ?: com.bitchat.android.geohash.ChannelID.Mesh
            
            // Update store state
            dispatch(ChatStore.Msg.SelectedLocationChannelChanged(effectiveChannelID))
            
            // Also update via LocationChannelManager for proper subscription handling
            locationChannelManager.select(effectiveChannelID)
        }

        // Peer actions - now using services directly
        private fun toggleFavorite(peerID: String) {
            scope.launch {
                try {
                    var fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
                    
                    // Fallback: if this looks like a 64-hex Noise public key (offline favorite entry),
                    // compute a synthetic fingerprint (SHA-256 of public key)
                    if (fingerprint == null && peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                        try {
                            val pubBytes = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            val fpBytes = digest.digest(pubBytes)
                            fingerprint = fpBytes.joinToString("") { "%02x".format(it) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to compute fingerprint from noise key hex: ${e.message}")
                        }
                    }
                    
                    if (fingerprint == null) {
                        Log.w(TAG, "toggleFavorite: no fingerprint for peerID=$peerID; ignoring toggle")
                        return@launch
                    }
                    
                    val wasFavorite = dataManager.isFavorite(fingerprint)
                    
                    if (wasFavorite) {
                        dataManager.removeFavorite(fingerprint)
                    } else {
                        dataManager.addFavorite(fingerprint)
                    }
                    
                    // Update state
                    val newFavorites = dataManager.favoritePeers.toSet()
                    dispatch(ChatStore.Msg.FavoritePeersUpdated(newFavorites))
                    
                    // Persist relationship in FavoritesPersistenceService
                    try {
                        var noiseKey: ByteArray? = null
                        var nickname: String = meshService.getPeerNicknames()[peerID] ?: peerID
                        
                        // Case 1: Live mesh peer with known info
                        val peerInfo = meshService.getPeerInfo(peerID)
                        if (peerInfo?.noisePublicKey != null) {
                            noiseKey = peerInfo.noisePublicKey
                            nickname = peerInfo.nickname
                        } else {
                            // Case 2: Offline favorite entry using 64-hex noise public key as peerID
                            if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                                try {
                                    noiseKey = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                                    // Prefer nickname from favorites store if available
                                    val rel = favoritesService.getFavoriteStatus(noiseKey)
                                    if (rel != null) nickname = rel.peerNickname
                                } catch (_: Exception) { }
                            }
                        }
                        
                        if (noiseKey != null) {
                            favoritesService.updateFavoriteStatus(
                                noisePublicKey = noiseKey,
                                nickname = nickname,
                                isFavorite = !wasFavorite
                            )
                            
                            // Send favorite notification via mesh or Nostr
                            try {
                                val myNostr = com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(
                                    applicationContext
                                )
                                val announcementContent = if (!wasFavorite) "[FAVORITED]:${myNostr?.npub ?: ""}" else "[UNFAVORITED]:${myNostr?.npub ?: ""}"
                                
                                if (meshService.hasEstablishedSession(peerID)) {
                                    meshService.sendPrivateMessage(
                                        announcementContent,
                                        peerID,
                                        nickname,
                                        java.util.UUID.randomUUID().toString()
                                    )
                                }
                            } catch (_: Exception) { }
                        }
                    } catch (_: Exception) { }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle favorite", e)
                    publish(ChatStore.Label.ShowError(e.message ?: "Failed to toggle favorite"))
                }
            }
        }

        private fun setNickname(nickname: String) {
            // Save to persistence
            dataManager.saveNickname(nickname)
            // Update store state
            dispatch(ChatStore.Msg.NicknameChanged(nickname))
            // Store is now the source of truth for nickname
        }

        // Password prompt
        private fun submitChannelPassword(channel: String, password: String) {
            scope.launch {
                joinChannel(channel, password)
                dismissPasswordPrompt()
            }
        }

        private fun dismissPasswordPrompt() {
            dispatch(ChatStore.Msg.PasswordPromptStateChanged(false, null))
        }

        // App lifecycle
        private fun setAppBackgroundState(isBackground: Boolean) {
            meshService.connectionManager.setAppBackgroundState(isBackground)
        }
        
        // Notification management - TODO: Implement notification service
        private fun clearNotificationsForSender(senderID: String) {
            Log.d(TAG, "Clear notifications for sender: $senderID")
        }
        
        private fun clearNotificationsForGeohash(geohash: String) {
            Log.d(TAG, "Clear notifications for geohash: $geohash")
        }
        
        // Command/Mention suggestions - implemented in Store
        private fun updateCommandSuggestions(input: String) {
            val suggestions = if (input.startsWith("/")) {
                val query = input.lowercase()
                listOf(
                    CommandSuggestion("/help", listOf("/?"), null, "show available commands"),
                    CommandSuggestion("/nick", emptyList(), "<name>", "set your nickname"),
                    CommandSuggestion("/join", listOf("/j"), "<channel>", "join or create a channel"),
                    CommandSuggestion("/leave", listOf("/part"), "[channel]", "leave current or specified channel"),
                    CommandSuggestion("/msg", listOf("/m"), "<user> [message]", "start private chat"),
                    CommandSuggestion("/who", listOf("/w"), null, "show online peers"),
                    CommandSuggestion("/channels", emptyList(), null, "show all channels"),
                    CommandSuggestion("/block", emptyList(), "[user]", "block user or list blocked"),
                    CommandSuggestion("/unblock", emptyList(), "<user>", "unblock user"),
                    CommandSuggestion("/clear", emptyList(), null, "clear messages"),
                    CommandSuggestion("/hug", emptyList(), "<user>", "send a hug"),
                    CommandSuggestion("/slap", emptyList(), "<user>", "slap with a trout")
                ).filter { it.command.startsWith(query) || it.aliases.any { alias -> alias.startsWith(query) } }
            } else {
                emptyList()
            }
            dispatch(ChatStore.Msg.CommandSuggestionsUpdated(suggestions.isNotEmpty(), suggestions))
        }
        
        private fun updateMentionSuggestions(input: String) {
            val lastWord = input.substringAfterLast(" ")
            val suggestions = if (lastWord.startsWith("@") && lastWord.length > 1) {
                val query = lastWord.substring(1).lowercase()
                state().connectedPeers.mapNotNull { peerID ->
                    val nickname = state().peerNicknames[peerID] ?: peerID
                    if (nickname.lowercase().contains(query)) nickname else null
                }.take(5)
            } else {
                emptyList()
            }
            dispatch(ChatStore.Msg.MentionSuggestionsUpdated(suggestions.isNotEmpty(), suggestions))
        }
        
        // Geohash actions
        private fun teleportToGeohash(geohash: String) {
            try {
                val level = when (geohash.length) {
                    in 0..2 -> com.bitchat.android.geohash.GeohashChannelLevel.REGION
                    in 3..4 -> com.bitchat.android.geohash.GeohashChannelLevel.PROVINCE
                    5 -> com.bitchat.android.geohash.GeohashChannelLevel.CITY
                    6 -> com.bitchat.android.geohash.GeohashChannelLevel.NEIGHBORHOOD
                    else -> com.bitchat.android.geohash.GeohashChannelLevel.BLOCK
                }
                val channel = com.bitchat.android.geohash.GeohashChannel(level, geohash.lowercase())
                locationChannelManager.setTeleported(true)
                locationChannelManager.select(com.bitchat.android.geohash.ChannelID.Location(channel))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to teleport to geohash: $geohash", e)
            }
        }
        
        private fun refreshLocationChannels() {
            // Refresh directly using the location channel manager
            locationChannelManager.refreshChannels()
        }
        
        private fun toggleGeohashBookmark(geohash: String) {
            // Toggle bookmark directly using the store
            geohashBookmarksStore.toggle(geohash)
            // State will be updated via the subscription to geohashBookmarks flow
        }
        
        // Peer blocking
        private fun blockPeer(peerID: String) {
            scope.launch {
                try {
                    val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
                    if (fingerprint != null) {
                        dataManager.addBlockedUser(fingerprint)
                        
                        // End private chat if currently in one with this peer
                        if (state().selectedPrivateChatPeer == peerID) {
                            endPrivateChat()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to block peer", e)
                    publish(ChatStore.Label.ShowError(e.message ?: "Failed to block peer"))
                }
            }
        }
        
        private fun unblockPeer(peerID: String) {
            scope.launch {
                try {
                    val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
                    if (fingerprint != null && dataManager.isUserBlocked(fingerprint)) {
                        dataManager.removeBlockedUser(fingerprint)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unblock peer", e)
                    publish(ChatStore.Label.ShowError(e.message ?: "Failed to unblock peer"))
                }
            }
        }
        
        private fun blockUserInGeohash(nickname: String) {
            // Direct implementation using GeohashRepository
            val pubkey = geohashRepository.findPubkeyByNickname(nickname)
            if (pubkey != null) {
                dataManager.addGeohashBlockedUser(pubkey)
                // Refresh people list and counts to remove blocked entry immediately
                geohashRepository.refreshGeohashPeople()
                geohashRepository.updateReactiveParticipantCounts()
                addSystemMessage("blocked $nickname in geohash channels")
            } else {
                addSystemMessage("user '$nickname' not found in current geohash")
            }
        }
        
        // Command/Mention selection - clear suggestions state
        private fun selectCommandSuggestion(suggestion: CommandSuggestion) {
            dispatch(ChatStore.Msg.CommandSuggestionsCleared)
        }
        
        private fun selectMentionSuggestion(nickname: String, currentText: String) {
            dispatch(ChatStore.Msg.MentionSuggestionsCleared)
        }
        
        // Location services
        private fun enableLocationChannels() {
            locationChannelManager.enableLocationChannels()
        }
        
        private fun enableLocationServices() {
            locationChannelManager.enableLocationServices()
        }
        
        private fun disableLocationServices() {
            locationChannelManager.disableLocationServices()
        }
        
        private fun setTeleported(teleported: Boolean) {
            locationChannelManager.setTeleported(teleported)
        }
        
        private fun beginLiveRefresh() {
            locationChannelManager.beginLiveRefresh()
        }
        
        private fun endLiveRefresh() {
            locationChannelManager.endLiveRefresh()
        }
        
        // Debug
        private fun getDebugStatus() {
            // Get debug status from mesh service directly
            val status = meshService.getDebugStatus()
            Log.d(TAG, status)
        }
        
        // Emergency actions
        private fun panicClearAllData() {
            Log.w(TAG, "🚨 PANIC MODE ACTIVATED - Clearing all sensitive data")
            
            // Clear persisted data
            dataManager.clearAllData()
            geohashBookmarksStore.clearAll()
            
            // Reset store state to initial
            dispatch(ChatStore.Msg.MessagesUpdated(emptyList()))
            dispatch(ChatStore.Msg.ChannelMessagesUpdated(emptyMap()))
            dispatch(ChatStore.Msg.PrivateChatsUpdated(emptyMap()))
            dispatch(ChatStore.Msg.JoinedChannelsUpdated(emptySet()))
            dispatch(ChatStore.Msg.CurrentChannelChanged(null))
            dispatch(ChatStore.Msg.SelectedPrivateChatPeerChanged(null))
            dispatch(ChatStore.Msg.UnreadPrivateMessagesUpdated(emptySet()))
            dispatch(ChatStore.Msg.UnreadChannelMessagesUpdated(emptyMap()))
            dispatch(ChatStore.Msg.FavoritePeersUpdated(emptySet()))
            dispatch(ChatStore.Msg.GeohashBookmarksUpdated(emptyList()))
            
            // Store is now the source of truth - no ChatViewModel sync needed
            Log.w(TAG, "🚨 PANIC CLEAR COMPLETE")
        }
    }

    private object ReducerImpl : Reducer<ChatStore.State, ChatStore.Msg> {
        override fun ChatStore.State.reduce(msg: ChatStore.Msg): ChatStore.State =
            when (msg) {
                // Messages
                is ChatStore.Msg.MessagesUpdated -> copy(messages = msg.messages)
                is ChatStore.Msg.ChannelMessagesUpdated -> copy(channelMessages = msg.channelMessages)
                is ChatStore.Msg.PrivateChatsUpdated -> copy(privateChats = msg.privateChats)
                is ChatStore.Msg.MessageAdded -> copy(messages = messages + msg.message)
                
                // Connection
                is ChatStore.Msg.ConnectionStateChanged -> copy(isConnected = msg.isConnected)
                is ChatStore.Msg.ConnectedPeersUpdated -> copy(connectedPeers = msg.peers)
                is ChatStore.Msg.MyPeerIDSet -> copy(myPeerID = msg.peerID)
                is ChatStore.Msg.NicknameChanged -> copy(nickname = msg.nickname)
                
                // Channels
                is ChatStore.Msg.JoinedChannelsUpdated -> copy(joinedChannels = msg.channels)
                is ChatStore.Msg.CurrentChannelChanged -> copy(currentChannel = msg.channel)
                is ChatStore.Msg.PasswordProtectedChannelsUpdated -> copy(passwordProtectedChannels = msg.channels)
                is ChatStore.Msg.UnreadChannelMessagesUpdated -> copy(unreadChannelMessages = msg.unread)
                
                // Private chats
                is ChatStore.Msg.SelectedPrivateChatPeerChanged -> copy(selectedPrivateChatPeer = msg.peerID)
                is ChatStore.Msg.UnreadPrivateMessagesUpdated -> copy(unreadPrivateMessages = msg.unread)
                
                // Location
                is ChatStore.Msg.SelectedLocationChannelChanged -> copy(selectedLocationChannel = msg.channelID)
                is ChatStore.Msg.TeleportedStateChanged -> copy(isTeleported = msg.isTeleported)
                is ChatStore.Msg.GeohashPeopleUpdated -> copy(geohashPeople = msg.people)
                is ChatStore.Msg.TeleportedGeoUpdated -> copy(teleportedGeo = msg.geo)
                is ChatStore.Msg.GeohashParticipantCountsUpdated -> copy(geohashParticipantCounts = msg.counts)
                is ChatStore.Msg.GeohashBookmarksUpdated -> copy(geohashBookmarks = msg.bookmarks)
                is ChatStore.Msg.GeohashBookmarkNamesUpdated -> copy(geohashBookmarkNames = msg.names)
                
                // Peer info
                is ChatStore.Msg.PeerSessionStatesUpdated -> copy(peerSessionStates = msg.states)
                is ChatStore.Msg.PeerFingerprintsUpdated -> copy(peerFingerprints = msg.fingerprints)
                is ChatStore.Msg.PeerNicknamesUpdated -> copy(peerNicknames = msg.nicknames)
                is ChatStore.Msg.PeerRSSIUpdated -> copy(peerRSSI = msg.rssi)
                is ChatStore.Msg.PeerDirectUpdated -> copy(peerDirect = msg.direct)
                is ChatStore.Msg.FavoritePeersUpdated -> copy(favoritePeers = msg.favorites)
                
                // Location permissions
                is ChatStore.Msg.LocationPermissionStateChanged -> copy(locationPermissionState = msg.state)
                is ChatStore.Msg.LocationServicesEnabledChanged -> copy(locationServicesEnabled = msg.enabled)
                
                // Network state
                is ChatStore.Msg.TorStatusChanged -> copy(torStatus = msg.status)
                is ChatStore.Msg.PoWEnabledChanged -> copy(powEnabled = msg.enabled)
                is ChatStore.Msg.PoWDifficultyChanged -> copy(powDifficulty = msg.difficulty)
                is ChatStore.Msg.IsMiningChanged -> copy(isMining = msg.isMining)
                
                // UI state
                is ChatStore.Msg.CommandSuggestionsUpdated -> copy(
                    showCommandSuggestions = msg.show,
                    commandSuggestions = msg.suggestions
                )
                is ChatStore.Msg.MentionSuggestionsUpdated -> copy(
                    showMentionSuggestions = msg.show,
                    mentionSuggestions = msg.suggestions
                )
                ChatStore.Msg.CommandSuggestionsCleared -> copy(
                    showCommandSuggestions = false,
                    commandSuggestions = emptyList()
                )
                ChatStore.Msg.MentionSuggestionsCleared -> copy(
                    showMentionSuggestions = false,
                    mentionSuggestions = emptyList()
                )
                is ChatStore.Msg.PasswordPromptStateChanged -> copy(
                    showPasswordPrompt = msg.show,
                    passwordPromptChannel = msg.channel
                )
                
                // Loading
                is ChatStore.Msg.LoadingChanged -> copy(isLoading = msg.isLoading)
                is ChatStore.Msg.SendingMessageChanged -> copy(isSendingMessage = msg.isSending)
            }
    }

    companion object {
        private const val TAG = "ChatStoreFactory"
    }
}
