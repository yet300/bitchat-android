package com.bitchat.android.ui.screens.chat

import androidx.activity.compose.BackHandler
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.bitchat.android.R
import com.bitchat.android.feature.chat.ChatComponent
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.model.CommandSuggestion
import com.bitchat.android.ui.*
import com.bitchat.android.ui.components.ModalBottomSheet
import com.bitchat.android.ui.events.FileShareDispatcher
import com.bitchat.android.ui.screens.chat.dialogs.PasswordPromptDialog
import com.bitchat.android.ui.screens.chat.sheets.AboutSheetContent
import com.bitchat.android.ui.screens.chat.sheets.ChatUserSheetContent
import com.bitchat.android.ui.screens.chat.sheets.LocationChannelsSheetContent
import com.bitchat.android.ui.screens.chat.sheets.LocationNotesSheetPresenterContent
import com.bitchat.android.ui.media.FullScreenImageViewer
import com.bitchat.android.ui.screens.chat.sheets.DebugSettingsSheetContent
import com.bitchat.android.ui.screens.chat.sheets.MeshPeerListSheetContent

/**
 * Main ChatScreen - REFACTORED to use component-based architecture
 * This is now a coordinator that orchestrates the following UI components:
 * - ChatHeader: App bar, navigation, peer counter
 * - MessageComponents: Message display and formatting
 * - InputComponents: Message input and command suggestions
 * - SidebarComponents: Navigation drawer with channels and people
 * - AboutSheet: App info and password prompts
 * - ChatUIUtils: Utility functions for formatting and colors
 */
@Composable
fun ChatScreen(
    component: ChatComponent
) {
    val colorScheme = MaterialTheme.colorScheme
    
    // Use component model for state (MVI pattern)
    val model by component.model.subscribeAsState()
    
    // Extract state from model
    val messages = model.messages
    val connectedPeers = model.connectedPeers
    val nickname = model.nickname
    val selectedPrivatePeer = model.selectedPrivateChatPeer
    val currentChannel = model.currentChannel
    val joinedChannels = model.joinedChannels
    val privateChats = model.privateChats
    val channelMessages = model.channelMessages
    val showCommandSuggestions = model.showCommandSuggestions
    val commandSuggestions = model.commandSuggestions
    val showMentionSuggestions = model.showMentionSuggestions
    val mentionSuggestions = model.mentionSuggestions
    
    // Handle back press
    val isBackHandlerEnabled = selectedPrivatePeer != null || currentChannel != null
    BackHandler(enabled = isBackHandlerEnabled) {
        when {
            selectedPrivatePeer != null -> component.onEndPrivateChat()
            currentChannel != null -> component.onSwitchToChannel(null)
        }
    }

    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    var viewerImagePaths by remember { mutableStateOf(emptyList<String>()) }
    var initialViewerIndex by remember { mutableStateOf(0) }
    var forceScrollToBottom by remember { mutableStateOf(false) }
    var isScrolledUp by remember { mutableStateOf(false) }

    val isConnected = model.isConnected
    val selectedLocationChannel = model.selectedLocationChannel

    // Determine what messages to show based on current context (unified timelines)
    val displayMessages = when {
        selectedPrivatePeer != null -> privateChats[selectedPrivatePeer] ?: emptyList()
        currentChannel != null -> channelMessages[currentChannel] ?: emptyList()
        else -> {
            val locationChannel = selectedLocationChannel
            if (locationChannel is ChannelID.Location) {
                val geokey = "geo:${locationChannel.channel.geohash}"
                channelMessages[geokey] ?: emptyList()
            } else {
                messages // Mesh timeline
            }
        }
    }

    // Determine whether to show media buttons (only hide in geohash location chats)
    val showMediaButtons = when {
        selectedPrivatePeer != null -> true
        currentChannel != null -> true
        else -> selectedLocationChannel !is ChannelID.Location
    }

    // Use WindowInsets to handle keyboard properly
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background) // Extend background to fill entire screen including status bar
    ) {
        val headerHeight = 42.dp
        
        // Main content area that responds to keyboard/window insets
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime) // This handles keyboard insets
                .windowInsetsPadding(WindowInsets.navigationBars) // Add bottom padding when keyboard is not expanded
        ) {
            // Header spacer - creates exact space for the floating header (status bar + compact header)
            Spacer(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(headerHeight)
            )

            // Messages area - takes up available space, will compress when keyboard appears
            MessagesList(
                messages = displayMessages,
                currentUserNickname = nickname,
                myPeerID = model.myPeerID,
                modifier = Modifier.weight(1f),
                forceScrollToBottom = forceScrollToBottom,
                onScrolledUpChanged = { isUp -> isScrolledUp = isUp },
                onNicknameClick = { fullSenderName ->
                    // Single click - mention user in text input
                    val currentText = messageText.text
                    
                    // Extract base nickname and hash suffix from full sender name
                    val (baseName, hashSuffix) = splitSuffix(fullSenderName)
                    
                    // Check if we're in a geohash channel to include hash suffix
                    val currentLocationChannel = model.selectedLocationChannel
                    val mentionText = if (currentLocationChannel is ChannelID.Location && hashSuffix.isNotEmpty()) {
                        // In geohash chat - include the hash suffix from the full display name
                        "@$baseName$hashSuffix"
                    } else {
                        // Regular chat - just the base nickname
                        "@$baseName"
                    }
                    
                    val newText = when {
                        currentText.isEmpty() -> "$mentionText "
                        currentText.endsWith(" ") -> "$currentText$mentionText "
                        else -> "$currentText $mentionText "
                    }
                    
                    messageText = TextFieldValue(
                        text = newText,
                        selection = TextRange(newText.length)
                    )
                },
                onMessageLongPress = { message ->
                    // Message long press - open user action sheet with message context
                    // Extract base nickname from message sender (contains all necessary info)
                    val (baseName, _) = splitSuffix(message.sender)
                    component.onShowUserSheet(baseName, message.id)
                },
                onCancelTransfer = { msg ->
                    component.onCancelMediaSend(msg.id)
                },
                onImageClick = { currentPath, allImagePaths, initialIndex ->
                    viewerImagePaths = allImagePaths
                    initialViewerIndex = initialIndex
                    showFullScreenImageViewer = true
                },
                onGeohashClick = { geohash ->
                    component.onTeleportToGeohash(geohash)
                }
            )
            // Input area - stays at bottom
        // Bridge file share from lower-level input to component
            LaunchedEffect(Unit) {
                FileShareDispatcher.setHandler { peer, channel, path ->
                    component.onSendFileNote(peer, channel, path)
                }
            }

    ChatInputSection(
        messageText = messageText,
        onMessageTextChange = { newText: TextFieldValue ->
            messageText = newText
            component.onUpdateCommandSuggestions(newText.text)
            component.onUpdateMentionSuggestions(newText.text)
        },
        onSend = {
            if (messageText.text.trim().isNotEmpty()) {
                component.onSendMessage(messageText.text.trim())
                messageText = TextFieldValue("")
                forceScrollToBottom = !forceScrollToBottom // Toggle to trigger scroll
            }
        },
        onSendVoiceNote = { peer, onionOrChannel, path ->
            component.onSendVoiceNote(peer, onionOrChannel, path)
        },
        onSendImageNote = { peer, onionOrChannel, path ->
            component.onSendImageNote(peer, onionOrChannel, path)
        },
        onSendFileNote = { peer, onionOrChannel, path ->
            component.onSendFileNote(peer, onionOrChannel, path)
        },
        
        showCommandSuggestions = showCommandSuggestions,
        commandSuggestions = commandSuggestions,
        showMentionSuggestions = showMentionSuggestions,
        mentionSuggestions = mentionSuggestions,
        onCommandSuggestionClick = { suggestion: CommandSuggestion ->
                    val commandText = component.onSelectCommandSuggestion(suggestion)
                    messageText = TextFieldValue(
                        text = commandText,
                        selection = TextRange(commandText.length)
                    )
                },
                onMentionSuggestionClick = { mention: String ->
                    val mentionText = component.onSelectMentionSuggestion(mention, messageText.text)
                    messageText = TextFieldValue(
                        text = mentionText,
                        selection = TextRange(mentionText.length)
                    )
                },
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                colorScheme = colorScheme,
                showMediaButtons = showMediaButtons
            )
        }

        // Floating header - positioned absolutely at top, ignores keyboard
        ChatFloatingHeader(
            headerHeight = headerHeight,
            selectedPrivatePeer = selectedPrivatePeer,
            currentChannel = currentChannel,
            nickname = nickname,
            model = model,
            component = component,
            colorScheme = colorScheme,
            onSidebarToggle = component::onShowMeshPeerList,
            onShowAppInfo = { component.onShowAppInfo() },
            onPanicClear = { component.onPanicClearAllData() },
            onLocationChannelsClick = { component.onShowLocationChannels() },
            onLocationNotesClick = {
                // Ensure location is loaded before showing sheet
                component.onRefreshLocationChannels()
                component.onShowLocationNotes()
            }
        )

        // Divider under header - positioned after status bar + header height
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .offset(y = headerHeight)
                .zIndex(1f),
            color = colorScheme.outline.copy(alpha = 0.3f)
        )

        // Scroll-to-bottom floating button
        AnimatedVisibility(
            visible = isScrolledUp,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 64.dp)
                .zIndex(1.5f)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .windowInsetsPadding(WindowInsets.ime)
        ) {
            Surface(
                shape = CircleShape,
                color = colorScheme.background,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(2.dp, Color(0xFF00C851))
            ) {
                IconButton(onClick = { forceScrollToBottom = !forceScrollToBottom }) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = stringResource(R.string.cd_scroll_to_bottom),
                        tint = Color(0xFF00C851)
                    )
                }
            }
        }
    }

    // Full-screen image viewer - separate from other sheets to allow image browsing without navigation
    if (showFullScreenImageViewer) {
        FullScreenImageViewer(
            imagePaths = viewerImagePaths,
            initialIndex = initialViewerIndex,
            onClose = { showFullScreenImageViewer = false }
        )
    }

    // Dialogs (using Decompose slot)
    ChatDialogs(
        component = component,
    )

    // Decompose-managed sheets
    ChatSheets(
        component = component,
        model = model
    )
}

@Composable
fun ChatInputSection(
    messageText: TextFieldValue,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onSendVoiceNote: (String?, String?, String) -> Unit,
    onSendImageNote: (String?, String?, String) -> Unit,
    onSendFileNote: (String?, String?, String) -> Unit,
    showCommandSuggestions: Boolean,
    commandSuggestions: List<CommandSuggestion>,
    showMentionSuggestions: Boolean,
    mentionSuggestions: List<String>,
    onCommandSuggestionClick: (CommandSuggestion) -> Unit,
    onMentionSuggestionClick: (String) -> Unit,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    colorScheme: ColorScheme,
    showMediaButtons: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colorScheme.background
    ) {
        Column {
            HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))
            // Command suggestions box
            if (showCommandSuggestions && commandSuggestions.isNotEmpty()) {
                CommandSuggestionsBox(
                    suggestions = commandSuggestions,
                    onSuggestionClick = onCommandSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.2f))
            }
            // Mention suggestions box
            if (showMentionSuggestions && mentionSuggestions.isNotEmpty()) {
                MentionSuggestionsBox(
                    suggestions = mentionSuggestions,
                    onSuggestionClick = onMentionSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.2f))
            }
            MessageInput(
                value = messageText,
                onValueChange = onMessageTextChange,
                onSend = onSend,
                onSendVoiceNote = onSendVoiceNote,
                onSendImageNote = onSendImageNote,
                onSendFileNote = onSendFileNote,
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                showMediaButtons = showMediaButtons,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatFloatingHeader(
    headerHeight: Dp,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    model: ChatComponent.Model,
    component: ChatComponent,
    colorScheme: ColorScheme,
    onSidebarToggle: () -> Unit,
    onShowAppInfo: () -> Unit,
    onPanicClear: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit
) {
    // Use state from model (MVI pattern) instead of viewModel
    val myPeerID = model.myPeerID
    val connectedPeers = model.connectedPeers
    val joinedChannels = model.joinedChannels
    val hasUnreadChannels = model.unreadChannelMessages
    val hasUnreadPrivateMessages = model.unreadPrivateMessages
    val isConnected = model.isConnected
    val selectedLocationChannel = model.selectedLocationChannel
    val geohashPeople = model.geohashPeople
    val isTeleported = model.isTeleported
    val torStatus = model.torStatus
    val powEnabled = model.powEnabled
    val powDifficulty = model.powDifficulty
    val isMining = model.isMining
    val permissionState = model.locationPermissionState
    val locationServicesEnabled = model.locationServicesEnabled
    val locationNotes = model.locationNotes
    val bookmarks = model.geohashBookmarks.toSet()
    val favoritePeers = model.favoritePeers
    val peerFingerprints = model.peerFingerprints
    val peerSessionStates = model.peerSessionStates
    val peerNicknames = model.peerNicknames
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .windowInsetsPadding(WindowInsets.statusBars), // Extend into status bar area
        color = colorScheme.background // Solid background color extending into status bar
    ) {
        TopAppBar(
            title = {
                ChatHeaderContent(
                    selectedPrivatePeer = selectedPrivatePeer,
                    currentChannel = currentChannel,
                    nickname = nickname,
                    myPeerID = myPeerID,
                    connectedPeers = connectedPeers,
                    joinedChannels = joinedChannels.toSet(),
                    hasUnreadChannels = hasUnreadChannels,
                    hasUnreadPrivateMessages = hasUnreadPrivateMessages,
                    isConnected = isConnected,
                    selectedLocationChannel = selectedLocationChannel,
                    geohashPeople = geohashPeople,
                    isTeleported = isTeleported,
                    torStatus = torStatus,
                    powEnabled = powEnabled,
                    powDifficulty = powDifficulty,
                    isMining = isMining,
                    permissionState = permissionState,
                    locationServicesEnabled = locationServicesEnabled,
                    locationNotes = locationNotes,
                    bookmarks = bookmarks.toSet(),
                    favoritePeers = favoritePeers,
                    peerFingerprints = peerFingerprints,
                    peerSessionStates = peerSessionStates,
                    peerNicknames = peerNicknames,
                    onBackClick = {
                        when {
                            selectedPrivatePeer != null -> component.onEndPrivateChat()
                            currentChannel != null -> component.onSwitchToChannel(null)
                        }
                    },
                    onSidebarClick = onSidebarToggle,
                    onTripleClick = onPanicClear,
                    onShowAppInfo = onShowAppInfo,
                    onLocationChannelsClick = onLocationChannelsClick,
                    onLocationNotesClick = onLocationNotesClick,
                    onNicknameChange = component::onSetNickname,
                    onToggleFavorite = component::onToggleFavorite,
                    onLeaveChannel = component::onLeaveChannel,
                    onOpenLatestUnreadPrivateChat = component::onOpenLatestUnreadPrivateChat,
                    onToggleGeohashBookmark = component::onToggleGeohashBookmark,
                    onGetFavoriteStatus = component::getFavoriteStatus
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            ),
            modifier = Modifier.height(headerHeight) // Ensure compact header height
        )
    }
}

@Composable
private fun ChatSheets(
    component: ChatComponent,
    model: ChatComponent.Model
) {
    val sheetSlot by component.sheetSlot.subscribeAsState()

    sheetSlot.child?.instance?.let { child ->
        ModalBottomSheet(
            onDismiss = component::onDismissSheet
        ) { listState ->
            when (child) {
                is ChatComponent.SheetChild.AppInfo -> {
                    AboutSheetContent(
                        component = child.component,
                        lazyListState = listState,
                        onShowDebug = { component.onShowDebugSettings() }
                    )
                }

                is ChatComponent.SheetChild.LocationChannels -> {
                    LocationChannelsSheetContent(
                        component = child.component,
                        lazyListState = listState,
                    )
                }

                is ChatComponent.SheetChild.LocationNotes -> {
                    LocationNotesSheetPresenterContent(
                        component = child.component,
                        lazyListState = listState,
                    )
                }

                is ChatComponent.SheetChild.UserSheet -> {
                    ChatUserSheetContent(
                        component = child.component,
                        lazyListState = listState
                    )
                }

                is ChatComponent.SheetChild.MeshPeerList -> {
                    MeshPeerListSheetContent(
                        component = child.component,
                        lazyListState = listState
                    )
                }
                
                is ChatComponent.SheetChild.DebugSettings -> {
                    DebugSettingsSheetContent(
                        component = child.component,
                        lazyListState = listState
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatDialogs(
    component: ChatComponent,
) {
    val dialogSlot by component.dialogSlot.subscribeAsState()

    dialogSlot.child?.instance?.let { child ->
        when (child) {
            is ChatComponent.DialogChild.PasswordPrompt -> {
                val model by child.component.model.subscribeAsState()

                PasswordPromptDialog(
                    show = true,
                    channelName = model.channelName,
                    passwordInput = model.passwordInput,
                    onPasswordChange = child.component::onPasswordChange,
                    onConfirm = child.component::onConfirm,
                    onDismiss = child.component::onDismiss
                )
            }
        }
    }
}
