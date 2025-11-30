# MVI Migration: ChatViewModel/ChatState → Store

## Goal
Replace MVVM pattern (ChatViewModel + ChatState) with MVI pattern (Store.State as single source of truth).

## Current State

### ✅ Completed
- **Components**: `DefaultChatComponent`, `DefaultMeshPeerListComponent` - no ViewModel dependencies
- **LocationChannelsStoreFactory**: Fully migrated, uses services directly
- **MeshEventBus**: Created, implements `BluetoothMeshDelegate`, exposes SharedFlows

### 🔄 Partially Migrated
- **ChatStoreFactory**: 55 ChatViewModel usages remain
- **MeshPeerListStoreFactory**: 20 ChatViewModel usages remain

## Remaining Work

### 1. Wire MeshEventBus as BluetoothMeshService Delegate

**File**: `DefaultRootComponent.kt`

```kotlin
// Current:
meshService.delegate = chatViewModel

// Change to:
meshService.delegate = meshEventBus
```

This makes MeshEventBus receive all mesh events, which it forwards to Store via SharedFlows.

### 2. Remove ChatViewModel Flow Subscriptions

**File**: `ChatStoreFactory.kt`

Remove subscriptions to ChatViewModel flows that now come from MeshEventBus:
- `chatViewModel.messages` → Store handles via `MeshEventBus.messageReceived`
- `chatViewModel.connectedPeers` → Already using `MeshEventBus.connectedPeers`
- `chatViewModel.privateChats` → Store manages directly
- `chatViewModel.channelMessages` → Store manages directly

### 3. Migrate Remaining Action Methods

**ChatStoreFactory methods still calling ChatViewModel:**

| Method | Migration Target |
|--------|-----------------|
| `sendMessage()` (geohash) | `GeohashViewModel.sendGeohashMessage()` |
| `sendVoiceNote/ImageNote/FileNote()` | Extract `MediaSendingManager` as service |
| `startPrivateChat()` | Implement in Store directly |
| `endPrivateChat()` | Implement in Store directly |
| `switchToChannel()` | Implement in Store directly |
| `leaveChannel()` | Implement in Store directly |
| `setNickname()` | Use `DataManager` directly |
| `updateCommandSuggestions()` | Implement in Store |
| `updateMentionSuggestions()` | Implement in Store |
| `setAppBackgroundState()` | Use `BluetoothMeshService` directly |
| `clearNotificationsForSender()` | Extract notification logic |

### 4. Remove ChatViewModel from DefaultRootComponent

Once all Store factories use services directly:
1. Remove `chatViewModel` constructor parameter
2. Remove ChatViewModel from Koin module
3. Delete `ChatViewModel.kt` and `ChatState.kt`

## Architecture Target

```
BluetoothMeshService
        │
        ▼ delegate
   MeshEventBus ──────────────────┐
        │                         │
        ▼ SharedFlows             │
   ChatStore ◄────────────────────┤
        │                         │
        │ State                   │ Services
        ▼                         │
DefaultChatComponent              │
        │                         │
        ▼ Model                   │
   ChatScreen                     │
                                  │
   LocationChannelManager ────────┤
   GeohashBookmarksStore ─────────┤
   GeohashViewModel ──────────────┤
   TorManager ────────────────────┤
   PoWPreferenceManager ──────────┤
   DataManager ───────────────────┘
```

## Files to Eventually Delete

- `app/src/main/java/com/bitchat/android/ui/ChatViewModel.kt`
- `app/src/main/java/com/bitchat/android/ui/ChatState.kt`
- `app/src/main/java/com/bitchat/android/ui/MessageManager.kt` (merge into Store)
- `app/src/main/java/com/bitchat/android/ui/ChannelManager.kt` (merge into Store)
- `app/src/main/java/com/bitchat/android/ui/PrivateChatManager.kt` (merge into Store)

## Key Principle

**Store.State is the single source of truth.** Services dispatch events → Store receives via flows → Reducer updates State → UI observes State.

No external mutable state (ChatState). All state lives in Store.State and is updated only through Messages/Reducer.
