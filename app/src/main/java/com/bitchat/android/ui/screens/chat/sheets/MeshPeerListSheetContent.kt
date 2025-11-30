package com.bitchat.android.ui.screens.chat.sheets

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.bitchat.android.R
import com.bitchat.android.feature.chat.meshpeerlist.MeshPeerListComponent
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.nostr.Bech32
import com.bitchat.android.ui.GeohashPeopleList
import com.bitchat.android.ui.splitSuffix
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import com.bitchat.android.ui.truncateNickname


/**
 * Sheet components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */
@Composable
fun MeshPeerListSheetContent(
    component: MeshPeerListComponent,
    lazyListState: LazyListState,
    modifier: Modifier = Modifier
) {
    val model by component.model.subscribeAsState()
    val colorScheme = MaterialTheme.colorScheme

    // Track nested private chat sheet state
    var showPrivateChatSheet by remember { mutableStateOf(false) }
    var privateChatPeerID by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 64.dp, bottom = 20.dp)
        ) {
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.your_network).uppercase(), // "YOUR NETWORK"
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            // Channels section
            if (model.joinedChannels.isNotEmpty()) {
                item(key = "channels_header") {
                    Text(
                        text = stringResource(id = R.string.channels).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSurface.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(top = 8.dp, bottom = 4.dp)
                    )
                }

                items(
                    items = model.joinedChannels.toList(),
                    key = { "channel_$it" }
                ) { channel ->
                    val isSelected = channel == model.currentChannel
                    val unreadCount = model.unreadChannelMessages[channel] ?: 0

                    ChannelRow(
                        channel = channel,
                        isSelected = isSelected,
                        unreadCount = unreadCount,
                        colorScheme = colorScheme,
                        onChannelClick = {
                            // Check if this is a DM channel (starts with @)
                            if (channel.startsWith("@")) {
                                // Extract peer name and find the peer ID
                                val peerName = channel.removePrefix("@")
                                val peerID = model.peerNicknames.entries.firstOrNull { it.value == peerName }?.key
                                if (peerID != null) {
                                    privateChatPeerID = peerID
                                    showPrivateChatSheet = true
                                }
                            } else {
                                component.onSwitchToChannel(channel)
                            }
                        },
                        onLeaveChannel = { component.onLeaveChannel(channel) }
                    )
                }
            }

            // People section - switch between mesh and geohash lists (iOS-compatible)
            item(key = "people_section") {
                when (model.selectedLocationChannel) {
                    is ChannelID.Location -> {
                        // Show geohash people list when in location channel
                        GeohashPeopleList(
                            geohashPeople = model.geohashPeople,
                            selectedLocationChannel = model.selectedLocationChannel,
                            isTeleported = model.isTeleported,
                            nickname = model.nickname,
                            unreadPrivateMessages = model.unreadPrivateMessages,
                            onStartGeohashDM = component::onStartGeohashDM,
                            onGetPersonTeleported = component::isPersonTeleported,
                            onGetColorForPubkey = component::colorForNostrPubkey,
                            onTapPerson = component::onDismiss
                        )
                    }

                    else -> {
                        // Show mesh peer list when in mesh channel (default)
                        PeopleSection(
                            model = model,
                            component = component,
                            colorScheme = colorScheme,
                            onPrivateChatStart = { peerID ->
                                component.onStartPrivateChat(peerID)
                                privateChatPeerID = peerID
                                showPrivateChatSheet = true
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: String,
    isSelected: Boolean,
    unreadCount: Int,
    colorScheme: ColorScheme,
    onChannelClick: () -> Unit,
    onLeaveChannel: () -> Unit
) {
    Surface(
        onClick = onChannelClick,
        color = if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.15f) else Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Unread badge
                if (unreadCount > 0) {
                    UnreadBadge(count = unreadCount, colorScheme = colorScheme)
                }

                Text(
                    text = channel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = BASE_FONT_SIZE.sp
                    ),
                    color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Selection indicator
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = Color(0xFF32D74B), // iOS green
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onLeaveChannel, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_leave_channel),
                        modifier = Modifier.size(16.dp),
                        tint = colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}


@Composable
private fun PeopleSection(
    model: MeshPeerListComponent.Model,
    component: MeshPeerListComponent,
    colorScheme: ColorScheme,
    onPrivateChatStart: (String) -> Unit
) {
    val modifier = Modifier.padding(top = if (model.joinedChannels.isNotEmpty()) 16.dp else 0.dp)
    
    Column(modifier = modifier) {
        Text(
            text = stringResource(id = R.string.people).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = colorScheme.onSurface.copy(alpha = 0.7f),
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 4.dp)
        )

        if (model.connectedPeers.isEmpty()) {
            Text(
                text = stringResource(id = R.string.no_one_connected),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                color = colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp, vertical = 12.dp)
            )
        }

        // Reactive favorite computation
        val peerFavoriteStates = remember(model.favoritePeers, model.peerFingerprints, model.connectedPeers) {
            model.connectedPeers.associateWith { peerID ->
                val fingerprint = model.peerFingerprints[peerID]
                fingerprint != null && model.favoritePeers.contains(fingerprint)
            }
        }

        // Build noise hex mapping
        val noiseHexByPeerID: Map<String, String> = model.connectedPeers.associateWith { pid ->
            component.getPeerNoisePublicKeyHex(pid)
        }.filterValues { it != null }.mapValues { it.value!! }

        // Smart sorting
        val sortedPeers = model.connectedPeers.sortedWith(
            compareBy<String> { !model.unreadPrivateMessages.contains(it) }
                .thenByDescending { model.privateChats[it]?.maxByOrNull { msg -> msg.timestamp }?.timestamp?.time ?: 0L }
                .thenBy { !(peerFavoriteStates[it] ?: false) }
                .thenBy { (if (it == model.nickname) "You" else (model.peerNicknames[it] ?: it)).lowercase() }
        )

        // Build base name counts
        val hex64Regex = Regex("^[0-9a-fA-F]{64}$")
        fun computeDisplayName(key: String): String {
            return if (key == model.nickname) "You" else (model.peerNicknames[key] ?: (model.privateChats[key]?.lastOrNull()?.sender ?: key.take(12)))
        }

        val baseNameCounts = mutableMapOf<String, Int>()

        // Connected peers
        sortedPeers.forEach { pid ->
            val (b, _) = splitSuffix(computeDisplayName(pid))
            if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
        }

        val offlineFavorites = component.getOfflineFavorites()
        offlineFavorites.forEach { fav ->
            val favPeerID = fav.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }
            val isMappedToConnected = noiseHexByPeerID.values.any { it.equals(favPeerID, ignoreCase = true) }
            if (!isMappedToConnected) {
                val dn = model.peerNicknames[favPeerID] ?: fav.peerNickname
                val (b, _) = splitSuffix(dn)
                if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
            }
        }

        // Render connected peers
        sortedPeers.forEach { peerID ->
            val isFavorite = peerFavoriteStates[peerID] ?: false
            val noiseHex = noiseHexByPeerID[peerID]
            val meshUnread = model.unreadPrivateMessages.contains(peerID)
            val nostrUnread = if (noiseHex != null) model.unreadPrivateMessages.contains(noiseHex) else false
            val combinedHasUnread = meshUnread || nostrUnread
            val combinedUnreadCount = (model.privateChats[peerID]?.count { msg -> msg.sender != model.nickname && meshUnread } ?: 0) +
                    (if (noiseHex != null) model.privateChats[noiseHex]?.count { msg -> msg.sender != model.nickname && nostrUnread } ?: 0 else 0)

            val displayName = computeDisplayName(peerID)
            val (bName, _) = splitSuffix(displayName)
            val showHash = (baseNameCounts[bName] ?: 0) > 1
            val isDirectLive = model.peerDirect[peerID] ?: component.isPeerDirectConnection(peerID)

            PeerItem(
                peerID = peerID,
                displayName = displayName,
                isDirect = isDirectLive,
                isSelected = peerID == model.selectedPrivatePeer,
                isFavorite = isFavorite,
                hasUnreadDM = combinedHasUnread,
                colorScheme = colorScheme,
                nickname = model.nickname,
                onColorForMeshPeer = component::colorForMeshPeer,
                onItemClick = { onPrivateChatStart(peerID) },
                onToggleFavorite = { component.onToggleFavorite(peerID) },
                unreadCount = if (combinedUnreadCount > 0) combinedUnreadCount else if (combinedHasUnread) 1 else 0,
                showNostrGlobe = false,
                showHashSuffix = showHash
            )
        }

        // Render offline favorites
        val appendedOfflineIds = mutableSetOf<String>()
        offlineFavorites.forEach { fav ->
            val favPeerID = fav.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }
            // If any connected peer maps to this noise key, skip showing the offline entry
            val isMappedToConnected =
                noiseHexByPeerID.values.any { it.equals(favPeerID, ignoreCase = true) }
            if (isMappedToConnected) return@forEach

            // Resolve potential Nostr conversation key for this favorite (for unread detection)
            val nostrConvKey: String? = try {
                val npubOrHex = component.findNostrPubkey(fav.peerNoisePublicKey)
                if (npubOrHex != null) {
                    val hex = if (npubOrHex.startsWith("npub")) {
                        val (hrp, data) = Bech32.decode(npubOrHex)
                        if (hrp == "npub") data.joinToString("") { "%02x".format(it) } else null
                    } else npubOrHex.lowercase()
                    hex?.let { "nostr_${it.take(16)}" }
                } else null
            } catch (_: Exception) { null }

            val hasUnread = model.unreadPrivateMessages.contains(favPeerID) || 
                    (nostrConvKey != null && model.unreadPrivateMessages.contains(nostrConvKey))

            val mappedConnectedPeerID = noiseHexByPeerID.entries.firstOrNull { it.value.equals(favPeerID, ignoreCase = true) }?.key
            val dn = model.peerNicknames[favPeerID] ?: fav.peerNickname
            val (bName, _) = splitSuffix(dn)
            val showHash = (baseNameCounts[bName] ?: 0) > 1

            val unreadCount = (model.privateChats[favPeerID]?.count { msg -> msg.sender != model.nickname && model.unreadPrivateMessages.contains(favPeerID) } ?: 0) +
                    (if (nostrConvKey != null) model.privateChats[nostrConvKey]?.count { msg -> msg.sender != model.nickname && model.unreadPrivateMessages.contains(nostrConvKey) } ?: 0 else 0)

            PeerItem(
                peerID = favPeerID,
                displayName = dn,
                isDirect = false,
                isSelected = (mappedConnectedPeerID ?: favPeerID) == model.selectedPrivatePeer,
                isFavorite = true,
                hasUnreadDM = hasUnread,
                colorScheme = colorScheme,
                nickname = model.nickname,
                onColorForMeshPeer = component::colorForMeshPeer,
                onItemClick = { onPrivateChatStart(mappedConnectedPeerID ?: favPeerID) },
                onToggleFavorite = { component.onToggleFavorite(favPeerID) },
                unreadCount = if (unreadCount > 0) unreadCount else if (hasUnread) 1 else 0,
                showNostrGlobe = (fav.isMutual && fav.peerNostrPublicKey != null),
                showHashSuffix = showHash
            )
            appendedOfflineIds.add(favPeerID)
        }

        // NOTE: Do NOT append Nostr-only (nostr_*) conversations to the mesh people list.
        // Geohash DMs should appear in the GeohashPeople list for the active geohash, not in mesh offline contacts.
        // We intentionally remove previously-added behavior that mixed geohash DMs into mesh sidebar.
        // If you need to surface non-geohash offline mesh conversations in the future, do it here for 64-hex noise IDs only.
        /*
        val alreadyShownIds = connectedIds + appendedOfflineIds
        privateChats.keys
            .filter { key ->
                // Only include 64-hex noise IDs (mesh identities); exclude any nostr_* aliases
                hex64Regex.matches(key) &&
                !alreadyShownIds.contains(key) &&
                // Skip if this key maps to a connected peer via noiseHex mapping
                !noiseHexByPeerID.values.any { it.equals(key, ignoreCase = true) }
            }
            .sortedBy { key -> privateChats[key]?.lastOrNull()?.timestamp }
            .forEach { convKey ->
                val lastSender = privateChats[convKey]?.lastOrNull()?.sender
                val dn = peerNicknames[convKey] ?: (lastSender ?: convKey.take(12))
                val (bName, _) = com.bitchat.android.ui.splitSuffix(dn)
                val showHash = (baseNameCounts[bName] ?: 0) > 1

                PeerItem(
                    peerID = convKey,
                    displayName = dn,
                    isDirect = false,
                    isSelected = convKey == selectedPrivatePeer,
                    isFavorite = false,
                    hasUnreadDM = hasUnreadPrivateMessages.contains(convKey),
                    colorScheme = colorScheme,
                    viewModel = viewModel,
                    onItemClick = { onPrivateChatStart(convKey) },
                    onToggleFavorite = { viewModel.toggleFavorite(convKey) },
                    unreadCount = privateChats[convKey]?.count { msg ->
                        msg.sender != nickname && hasUnreadPrivateMessages.contains(convKey)
                    } ?: if (hasUnreadPrivateMessages.contains(convKey)) 1 else 0,
                    showNostrGlobe = false,
                    showHashSuffix = showHash
                )
            }
        */
        // End intentional removal

    }
}

@Composable
private fun PeerItem(
    peerID: String,
    displayName: String,
    isDirect: Boolean,
    isSelected: Boolean,
    isFavorite: Boolean,
    hasUnreadDM: Boolean,
    colorScheme: ColorScheme,
    nickname: String,
    onColorForMeshPeer: (String, Boolean) -> Color,
    onItemClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    unreadCount: Int = 0,
    showNostrGlobe: Boolean = false,
    showHashSuffix: Boolean = true
) {
    // Split display name for hashtag suffix support (iOS-compatible)
    val (baseNameRaw, suffixRaw) = splitSuffix(displayName)
    val baseName = truncateNickname(baseNameRaw)
    val suffix = if (showHashSuffix) suffixRaw else ""
    val isMe = displayName == "You" || peerID == nickname

    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    val assignedColor = onColorForMeshPeer(peerID, isDark)
    val baseColor = if (isMe) Color(0xFFFF9500) else assignedColor

    Surface(
        onClick = onItemClick,
        color = if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.15f) else Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status icon
                when {
                    hasUnreadDM -> Icon(Icons.Filled.Email, stringResource(R.string.cd_unread_message), Modifier.size(16.dp), Color(0xFFFF9500))
                    showNostrGlobe -> Icon(Icons.Filled.Public, stringResource(R.string.cd_reachable_via_nostr), Modifier.size(16.dp), Color(0xFF9C27B0))
                    !isDirect && isFavorite -> Icon(Icons.Outlined.Circle, stringResource(R.string.cd_offline_favorite), Modifier.size(16.dp), Color.Gray)
                    else -> Icon(
                        if (isDirect) Icons.Outlined.SettingsInputAntenna else Icons.Filled.Route,
                        if (isDirect) "Direct Bluetooth" else "Routed",
                        Modifier.size(16.dp),
                        colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                // Display name with iOS-style color and hashtag suffix support
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Base name with peer-specific color
                    Text(
                        text = baseName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = BASE_FONT_SIZE.sp,
                            fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = baseColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Hashtag suffix in lighter shade (iOS-style)
                    if (suffix.isNotEmpty()) {
                        Text(
                            text = suffix,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = BASE_FONT_SIZE.sp
                            ),
                            color = baseColor.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Selection indicator
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = Color(0xFF32D74B), // iOS green
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Favorite star with proper filled/outlined states
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                        modifier = Modifier.size(16.dp),
                        tint = if (isFavorite) Color(0xFFFFD700) else Color(0xFF4CAF50)
                    )
                }
            }
        }
    }
}

/**
 * Reusable unread badge component for both channels and private messages
 */
@Composable
private fun UnreadBadge(
    count: Int,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier
) {
    if (count > 0) {
        Box(
            modifier = modifier
                .background(
                    color = Color(0xFFFFD700), // Yellow color
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                color = Color.Black
            )
        }
    }
}
