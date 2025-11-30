# Migration Status: MVI Architecture Migration

## Summary

Migrating from MVVM (ChatViewModel/GeohashViewModel/ChatState) to MVI (Stores with direct service subscriptions).

## Current ChatViewModel Usage Count

| File | ChatViewModel Usages |
|------|---------------------|
| ChatStoreFactory | 55 |
| MeshPeerListStoreFactory | 20 |
| LocationChannelsStoreFactory | 0 ✅ |

## Completed Migrations

### Components (100% Clean)
- **DefaultChatComponent** - No ChatViewModel/GeohashViewModel
- **DefaultMeshPeerListComponent** - No ChatViewModel/GeohashViewModel

### ChatStoreFactory - MVI Infrastructure Added
- Added `MeshEventBus` subscription for mesh events
- Added `setupMeshEventBusCallbacks()` for nickname/favorite/decryption
- Added `subscribeToMeshEvents()` for messages, delivery acks, read receipts
- Added `handleIncomingMessage()` to route messages to correct state
- Added `updateMessageDeliveryStatus()` for delivery tracking

### Flow Subscriptions Migrated to Services
- `connectedPeers` → `MeshEventBus.connectedPeers`
- `selectedLocationChannel` → `LocationChannelManager.selectedChannel`
- `isTeleported` → `LocationChannelManager.teleported`
- `locationPermissionState` → `LocationChannelManager.permissionState`
- `locationServicesEnabled` → `LocationChannelManager.locationServicesEnabled`
- `geohashBookmarks` → `GeohashBookmarksStore.bookmarks`
- `geohashBookmarkNames` → `GeohashBookmarksStore.bookmarkNames`
- `geohashPeople` → `GeohashViewModel.geohashPeople`
- `geohashParticipantCounts` → `GeohashViewModel.geohashParticipantCounts`
- `torStatus` → `TorManager.statusFlow`
- `powEnabled` → `PoWPreferenceManager.powEnabled`
- `powDifficulty` → `PoWPreferenceManager.powDifficulty`
- `isMining` → `PoWPreferenceManager.isMining`

### Action Methods Migrated
- `teleportToGeohash()` → Direct `LocationChannelManager` calls
- `refreshLocationChannels()` → `LocationChannelManager.refreshChannels()`
- `toggleGeohashBookmark()` → `GeohashBookmarksStore.toggle()`
- `blockUserInGeohash()` → `GeohashViewModel.blockUserInGeohash()`
- `startGeohashDM()` → `GeohashViewModel.startGeohashDM()`
- `selectLocationChannel()` → `LocationChannelManager.select()`
- Location service methods → `LocationChannelManager`

## MVI Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    BluetoothMeshService                     │
│                    (Mesh Networking)                        │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼ delegate callbacks
┌─────────────────────────────────────────────────────────────┐
│                      MeshEventBus                           │
│  - Implements BluetoothMeshDelegate                         │
│  - Exposes SharedFlows for events                           │
│  - Callbacks for nickname/favorites/decryption              │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼ Store subscribes to flows
┌─────────────────────────────────────────────────────────────┐
│                      ChatStore                              │
│  - State: Single source of truth                            │
│  - Intent: User actions                                     │
│  - Msg: Internal state updates                              │
│  - Reducer: Pure state transformation                       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼ Component observes state
┌─────────────────────────────────────────────────────────────┐
│                   DefaultChatComponent                      │
│  - Exposes Model (mapped from Store.State)                  │
│  - Handles navigation                                       │
│  - Dispatches Intents to Store                              │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼ UI observes Model
┌─────────────────────────────────────────────────────────────┐
│                      ChatScreen                             │
│  - Pure Composable UI                                       │
│  - Renders Model                                            │
│  - Calls Component methods for actions                      │
└─────────────────────────────────────────────────────────────┘
```

## Next Steps to Complete Migration

### 1. Wire MeshEventBus as Primary Delegate
In `DefaultRootComponent.kt`:
```kotlin
// Current:
meshService.delegate = chatViewModel

// Target: Create composite delegate or switch entirely
meshService.delegate = meshEventBus
```

### 2. Remove ChatViewModel Flow Subscriptions
Once MeshEventBus is the delegate, the Store will receive all mesh events directly. Remove:
- `chatViewModel.messages` subscription
- `chatViewModel.privateChats` subscription  
- `chatViewModel.channelMessages` subscription
- etc.

### 3. Migrate Remaining Action Methods
Methods that still call ChatViewModel:
- `sendMessage()` for geohash - use `GeohashViewModel.sendGeohashMessage()`
- `sendVoiceNote/ImageNote/FileNote` - extract MediaSendingManager
- `startPrivateChat/endPrivateChat` - implement directly in Store
- `switchToChannel/leaveChannel` - implement directly in Store
- `updateCommandSuggestions/updateMentionSuggestions` - implement in Store

### 4. Remove ChatViewModel Dependency
Once all state and actions are handled by Store + Services:
1. Remove `chatViewModel` from DefaultRootComponent constructor
2. Remove ChatViewModel from Koin
3. Delete ChatViewModel.kt and ChatState.kt

## Build Status

```bash
./gradlew assembleDebug
# BUILD SUCCESSFUL
```

## Key Files Modified

- `MeshEventBus.kt` - New service implementing BluetoothMeshDelegate
- `ChatStoreFactory.kt` - Added MeshEventBus subscriptions and callbacks
- `LocationChannelsStoreFactory.kt` - Fully migrated from ChatViewModel
- `MeshPeerListStoreFactory.kt` - Partially migrated
- `DefaultChatComponent.kt` - Removed GeohashViewModel
- `DefaultMeshPeerListComponent.kt` - Removed GeohashViewModel
