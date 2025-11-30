# Chat Feature Migration: MVVM → Decompose + MVIKotlin

## Overview

This document tracks the migration of the Chat feature from MVVM (ChatViewModel) to Decompose + MVIKotlin architecture.

## Migration Progress: ~85% Complete ✨

### Recent Session Highlights
- ✅ Created DebugComponent (proper Decompose pattern)
- ✅ Refactored DebugSettingsSheet → DebugSettingsSheetContent
- ✅ Deleted old DebugSettingsSheet.kt
- ✅ Fixed AboutSheetContent to use component navigation
- ✅ Refactored 3 UI components (TorStatusDot, PoWStatusIndicator, LocationNotesButton)
- ✅ All 7 major sheets now follow consistent Decompose pattern

### ✅ Completed
- **UI Layer**: ChatScreen fully uses ChatComponent (100%)
- **State Management**: All state flows through Store (100%)
- **Core Operations**: Peer management, channels, private chat, messaging (90%)
- **Service Integration**: 10 services directly injected into Store
- **Data Persistence**: Direct DataManager integration
- **Network State**: Tor status, PoW settings exposed through Store
- **Location State**: Permission state, services enabled exposed through Store

### 🔄 In Progress
- **Message Routing**: Basic sending migrated, commands still delegated
- **Location Services**: Core operations migrated, geohash messaging delegated
- **Media Sending**: Still delegated to MediaSendingManager

### ⏳ Remaining
- **Command Processing**: IRC-style commands (CommandProcessor)
- **Geohash Integration**: GeohashViewModel operations
- **Notifications**: NotificationManager
- **Mesh Callbacks**: MeshDelegateHandler
- **UI Components**: Sub-components still use ChatViewModel

## Current Status: Phase 3 In Progress (Manager Migration)

The UI layer is fully migrated to use ChatComponent. Now migrating the manager classes from ChatViewModel into the Store.

### Services Now Injected Directly in ChatStoreFactory
- ✅ `DataManager` - Used for nickname, channel data, favorites persistence
- ✅ `GeohashBookmarksStore` - Used for bookmark toggle
- ✅ `LocationChannelManager` - Used for refreshing location channels
- ✅ `BluetoothMeshService` - Used for peer ID and mesh operations
- ✅ `MessageRouter` - Available for message routing
- ✅ `PeerFingerprintManager` - For peer fingerprint operations
- ✅ `FavoritesPersistenceService` - For favorites management

### Functions Migrated to Store (Phase 3)
- ✅ `toggleFavorite()` - Now uses PeerFingerprintManager and FavoritesPersistenceService directly
- ✅ `joinChannel()` - Channel join logic with password handling
- ✅ `startPrivateChat()` - Private chat with Noise session establishment
- ✅ `endPrivateChat()` - Private chat cleanup
- ✅ `blockPeer()` / `unblockPeer()` - Peer blocking using fingerprints
- ✅ `sendMessage()` - Direct message sending with routing (private, channel, public)
- ✅ Message helper methods - `addMessage()`, `addChannelMessage()`, `addPrivateMessage()`
- ✅ `parseMentions()` - Mention parsing logic
- ✅ Location services - `enableLocationChannels()`, `setTeleported()`, `beginLiveRefresh()`
- ✅ **Command processing** - All 9 IRC-style commands: `/join`, `/msg`, `/clear`, `/who`, `/channels`, `/block`, `/unblock`, `/hug`, `/slap`
- ✅ Channel encryption key derivation (PBKDF2)
- ✅ Message deduplication tracking in executor

### Services Now Injected (10 total)
- ✅ `BluetoothMeshService` - Mesh operations
- ✅ `MessageRouter` - Message routing
- ✅ `DataManager` - Persistence
- ✅ `GeohashBookmarksStore` - Bookmarks
- ✅ `LocationChannelManager` - Location channels
- ✅ `PeerFingerprintManager` - Fingerprints
- ✅ `FavoritesPersistenceService` - Favorites
- ✅ `SeenMessageStore` - Message tracking
- ✅ `NostrRelayManager` - Nostr relays
- ✅ `NostrTransport` - Nostr transport

### Still Delegating to ChatViewModel
- 🔄 `sendVoiceNote/ImageNote/FileNote()` - Media sending via MediaSendingManager
- 🔄 Geohash message sending - GeohashViewModel integration
- 🔄 Command/mention autocomplete - Suggestion generation
- 🔄 Notification management - NotificationManager integration
- 🔄 BluetoothMeshDelegate callbacks - MeshDelegateHandler integration

### Out of Scope (Handled by Other Components)
- ❌ Theme preferences - Managed by About component
- ❌ Tor settings - Managed by About component
- ❌ App-wide settings - Not Chat's responsibility

## Phase 3 Progress Summary

### What's Been Migrated
The Store now handles:
- **Peer management**: Favorites, blocking, fingerprints
- **Channel management**: Join/leave with password protection
- **Private chat**: Start/end with Noise session establishment
- **Data persistence**: Direct DataManager integration
- **State management**: All UI state flows through Store

### What Remains
The following complex subsystems still delegate to ChatViewModel:
- **Message routing**: MessageRouter, MessageManager, MediaSendingManager
- **Command processing**: CommandProcessor for IRC-style commands
- **Geohash/Nostr**: GeohashViewModel for location channels
- **Notifications**: NotificationManager for push notifications
- **Mesh callbacks**: MeshDelegateHandler for BluetoothMeshDelegate

## Phase 4: Complete Migration (TODO)

To fully remove ChatViewModel, the following managers need to be migrated:

### Managers to Migrate
1. **MessageManager** - Message handling and formatting
2. **ChannelManager** - Channel join/leave/switch logic (tightly coupled with ChatState)
3. **PrivateChatManager** - Private chat state and Noise sessions
4. **MediaSendingManager** - Voice/image/file sending
5. **CommandProcessor** - IRC-style command handling
6. **NotificationManager** - Push notifications
7. **MeshDelegateHandler** - BluetoothMeshDelegate callbacks

### Services to Inject Directly
- BluetoothMeshService
- NostrRelayManager
- NostrTransport
- MessageRouter
- SeenMessageStore
- PeerFingerprintManager
- GeohashBookmarksStore
- LocationChannelManager
- FavoritesPersistenceService
- TorManager
- LocationNotesManager

### BluetoothMeshDelegate Implementation
The store needs to implement BluetoothMeshDelegate callbacks:
- onPeerConnected/onPeerDisconnected
- onMessageReceived
- onPrivateMessageReceived
- onNoiseSessionEstablished
- etc.

### GeohashViewModel Integration
The GeohashViewModel handles Nostr geohash messaging and needs to be integrated.

## Architecture

### Core Components

```
feature/chat/
├── ChatComponent.kt              # Interface defining component contract
├── DefaultChatComponent.kt       # Implementation with navigation logic
├── PreviewChatComponent.kt       # Preview implementation for Compose
├── store/
│   ├── ChatStore.kt             # MVIKotlin store interface (State, Intent, Label)
│   └── ChatStoreFactory.kt      # Store implementation with bridge to ChatViewModel
└── integration/
    └── Mappers.kt               # State-to-model transformation
```

### Pattern: Bridge for Gradual Migration

The `ChatStoreFactory` uses a bridge pattern to gradually migrate from ChatViewModel:
- Subscribes to ChatViewModel's StateFlows
- Dispatches messages to update store state
- Allows incremental migration of functionality

## Migration Status

### ✅ Completed

#### State Management
All state now flows through `ChatComponent.Model`:
- Messages (main timeline, channels, private chats)
- Connection state (connected, peers, myPeerID, nickname)
- Channel state (joined, current, password-protected, unread)
- Private chat state (selected peer, unread messages)
- Location/geohash state (selected channel, teleported, people, bookmarks)
- Peer info (session states, fingerprints, nicknames, RSSI, direct, favorites)
- UI state (command/mention suggestions, loading)

#### Actions
All user actions delegate to component methods:
- **Message actions**: send, voice/image/file notes, cancel
- **Channel actions**: join, switch, leave
- **Private chat actions**: start, end, open latest unread
- **Location actions**: select channel, teleport, refresh, toggle bookmark
- **Peer actions**: toggle favorite, set nickname
- **Suggestions**: update/select command and mention suggestions
- **Notifications**: clear for sender/geohash
- **Lifecycle**: app background state
- **Emergency**: panic clear all data

#### UI Layer
- `ChatScreen` fully migrated to use `component.model`
- No direct state collection from viewModel in ChatScreen
- Password prompt handled via store labels
- All user interactions go through component

### 🔄 Remaining Work

#### Sub-components Still Using ViewModel
These components receive viewModel as parameter and can be migrated incrementally:

1. **ChatHeaderContent** (`ui/ChatHeader.kt`)
   - Tor status display
   - Peer color utilities
   - Favorite status methods
   - Bookmark toggle

2. **MeshPeerListSheetContent** (`ui/screens/chat/sheets/MeshPeerListSheetContent.kt`)
   - Complex peer list with favorites
   - Offline favorites display
   - Nostr conversation keys
   - Peer color assignment

3. **DebugSettingsSheet** (`ui/debug/DebugSettingsSheet.kt`)
   - Debug settings management
   - Verbose logging toggle
   - Network diagnostics

## Benefits Achieved

### 1. Unidirectional Data Flow
```
User Action → Intent → Executor → Msg → Reducer → State → Model → UI
```

### 2. Testability
- Store logic isolated from UI
- Pure reducer functions
- Testable executors

### 3. State Preservation
- Decompose handles lifecycle
- State survives configuration changes
- Proper cleanup on destroy

### 4. Type Safety
- Sealed classes for Intents, Messages, Labels
- Compile-time guarantees
- No magic strings

### 5. Gradual Migration
- Bridge pattern allows incremental work
- No big-bang rewrite
- Production-ready at each step

## Code Examples

### Sending a Message (Before)
```kotlin
// In ChatScreen
viewModel.sendMessage(content)
```

### Sending a Message (After)
```kotlin
// In ChatScreen
component.onSendMessage(content)

// In DefaultChatComponent
override fun onSendMessage(content: String) {
    store.accept(ChatStore.Intent.SendMessage(content))
}

// In ChatStoreFactory
private fun sendMessage(content: String) {
    scope.launch {
        dispatch(ChatStore.Msg.SendingMessageChanged(true))
        chatViewModel.sendMessage(content)
        dispatch(ChatStore.Msg.SendingMessageChanged(false))
        publish(ChatStore.Label.MessageSent(messageId))
    }
}
```

### Observing State (Before)
```kotlin
// In ChatScreen
val messages by viewModel.messages.collectAsState()
val nickname by viewModel.nickname.collectAsState()
```

### Observing State (After)
```kotlin
// In ChatScreen
val model by component.model.subscribeAsState()
val messages = model.messages
val nickname = model.nickname
```

## Migration Guidelines

### For New Features
1. Add Intent to `ChatStore.Intent`
2. Add Msg to `ChatStore.Msg` if state changes
3. Add Label to `ChatStore.Label` if side effects needed
4. Implement in `ChatStoreFactory.executeIntent()`
5. Add reducer case if state changes
6. Add method to `ChatComponent` interface
7. Implement in `DefaultChatComponent`
8. Update `PreviewChatComponent` stub

### For Migrating Sub-components
1. Identify state needed from viewModel
2. Add to `ChatStore.State` if not present
3. Subscribe to viewModel StateFlow in `ChatStoreFactory`
4. Add to `ChatComponent.Model`
5. Update mapper in `Mappers.kt`
6. Update sub-component to use component.model
7. Remove viewModel parameter

## Testing

All tests pass after Phase 3 migration:
```bash
./gradlew test
# BUILD SUCCESSFUL in 20s
# 47 actionable tasks: 12 executed, 35 up-to-date
```

Full build succeeds:
```bash
./gradlew assembleDebug
# BUILD SUCCESSFUL in 20s
# 34 actionable tasks: 5 executed, 29 up-to-date
```

## Phase 3 Accomplishments

### Code Migrated (Latest Session)
- **State exposure**: Added missing state to Store for UI components
  - `torStatus` - Tor connection status and mode
  - `powEnabled`, `powDifficulty`, `isMining` - Proof-of-Work settings
  - `locationPermissionState`, `locationServicesEnabled` - Location state
  - All state now flows through Store, reducing direct ChatViewModel access
  
- **Command processing**: 200+ lines of IRC-style command handling
  - `/join <channel>` - Join/create channels with password support
  - `/msg <nickname> [message]` - Start private chat and send message
  - `/clear` - Clear messages (context-aware: private/channel/main)
  - `/who` - List online users (mesh peers or geohash participants)
  - `/channels` - List joined channels
  - `/block [nickname]` - Block user or list blocked users
  - `/unblock <nickname>` - Unblock user
  - `/hug <nickname>` - Send warm hug action
  - `/slap <nickname>` - Send trout slap action
  - All commands now process directly in Store (no CommandProcessor dependency)
  
- **sendMessage**: 70+ lines migrated with intelligent routing
  - Private message routing via MessageRouter
  - Channel message handling with unread tracking
  - Public message broadcasting
  - Mention parsing integrated
  - Command detection and processing
  - Geohash channel detection
  
- **Message helpers**: 80+ lines of message management
  - `addMessage()` - Main timeline messages
  - `addChannelMessage()` - Channel messages with unread tracking
  - `addPrivateMessage()` - Private chat messages
  - `parseMentions()` - @mention detection
  - Unread count management for channels and private chats
  
- **Location services**: 40+ lines migrated
  - `enableLocationChannels()` / `enableLocationServices()`
  - `setTeleported()` / `beginLiveRefresh()` / `endLiveRefresh()`
  - Direct LocationChannelManager integration

### Code Migrated (Previous Session)
- **toggleFavorite**: 60+ lines migrated from PrivateChatManager to Store
  - Direct PeerFingerprintManager integration
  - FavoritesPersistenceService for cross-device sync
  - Nostr favorite notifications
  
- **joinChannel**: 50+ lines migrated from ChannelManager to Store
  - Password-protected channel support
  - PBKDF2 key derivation (100k iterations)
  - Channel creator tracking
  - Persistence via DataManager
  
- **startPrivateChat**: 40+ lines migrated from PrivateChatManager to Store
  - Peer blocking checks
  - Noise session establishment (lexicographical handshake)
  - Unread message clearing
  - Chat initialization
  
- **blockPeer/unblockPeer**: 30+ lines migrated
  - Fingerprint-based blocking
  - Auto-close private chat on block
  - DataManager persistence

### Architecture Improvements
- **Direct service injection**: 10 services now injected into Store (up from 5)
- **Message routing**: Store now handles message sending with proper routing
- **Reduced coupling**: Store handles most core operations independently
- **Better testability**: Business logic isolated in executor
- **Type safety**: All operations go through sealed Intent classes

### New Intents Added
- Location: `EnableLocationChannels`, `EnableLocationServices`, `DisableLocationServices`
- Location: `SetTeleported`, `BeginLiveRefresh`, `EndLiveRefresh`
- Peer: `BlockPeer`, `UnblockPeer`, `BlockUserInGeohash`

### Architectural Decisions
- **Theme/Tor settings**: Kept in About component (not Chat's responsibility)
- **Service boundaries**: Each component manages its own domain
- **Microservices approach**: Chat handles messaging, About handles settings

### Lines of Code
- **Added to Store**: ~650 lines of business logic (cumulative)
- **State fields in Store**: 40+ fields (up from 30)
- **Commands migrated**: All 9 IRC commands (/join, /msg, /clear, /who, /channels, /block, /unblock, /hug, /slap)
- **CommandProcessor eliminated**: No longer needed for basic command processing
- **Services injected**: 10 (BluetoothMeshService, MessageRouter, DataManager, GeohashBookmarksStore, LocationChannelManager, PeerFingerprintManager, FavoritesPersistenceService, SeenMessageStore, NostrRelayManager, NostrTransport)
- **Bridge pattern**: Maintained for gradual migration of remaining subsystems

### Next Steps to Eliminate ChatViewModel from UI
Now that Store has all necessary state, UI components can be refactored to:
1. Receive state from `component.model` instead of `viewModel`
2. Call component methods instead of viewModel methods
3. Remove ChatViewModel parameter from component signatures

### UI Components Still Using ChatViewModel (Priority Order)
1. **ChatScreen** - Main screen, passes viewModel to children
   - Already uses `component.model` for most state
   - Needs to pass model data to child components instead of viewModel
   
2. **ChatHeader.kt** components:
   - `TorStatusDot` - ✅ Refactored to accept `torStatus` parameter
   - `PoWStatusIndicator` - ✅ Refactored to accept `powEnabled`, `powDifficulty`, `isMining`
   - `LocationNotesButton` - ✅ Refactored to accept location state parameters
   - `MainHeader` - ✅ Updated to collect and pass state to child components
   - `LocationChannelsButton` - Still needs refactoring
   
3. **GeohashPeopleList** - Needs `geohashPeople`, `selectedLocationChannel`, `isTeleported`

4. **MeshPeerListSheetContent** - Complex component with many dependencies

5. **DebugSettingsSheet** - ✅ Converted to DebugComponent (Decompose pattern)

### Refactoring Pattern
```kotlin
// Before:
@Composable
fun MyComponent(viewModel: ChatViewModel) {
    val state by viewModel.someState.collectAsState()
    // use state
}

// After:
@Composable
fun MyComponent(someState: SomeType) {
    // use state directly
}

// Called from parent:
val model by component.model.subscribeAsState()
MyComponent(someState = model.someState)
```

## Next Steps

### Immediate (Phase 3 Continuation)
1. ✅ ~~Migrate CommandProcessor logic into Store~~ **COMPLETE**
   - ✅ IRC-style command parsing
   - ✅ Command execution (all 9 commands)
   - 🔄 Command/mention autocomplete (suggestion generation)
   
2. Migrate MediaSendingManager
   - Voice note sending
   - Image note sending
   - File note sending
   - Transfer progress tracking

3. Integrate GeohashViewModel operations
   - Geohash message sending
   - Participant tracking
   - Teleport operations

### Later (Phase 4)
1. Migrate NotificationManager
   - Push notification logic
   - Notification clearing
   - Background state handling

2. Migrate MeshDelegateHandler
   - BluetoothMeshDelegate callbacks
   - Message reception
   - Peer connection events

3. UI Component Migration
   - Migrate `ChatHeaderContent` to use component
   - Migrate `MeshPeerListSheetContent` to use component
   - Migrate `DebugSettingsSheet` to use component

4. Final Cleanup
   - Remove ChatViewModel bridge
   - Remove ChatState class (state now in Store)
   - Remove manager classes (logic now in Store)

## References

- [Decompose Documentation](https://arkivanov.github.io/Decompose/)
- [MVIKotlin Documentation](https://arkivanov.github.io/MVIKotlin/)
- [TetrisLite Reference Implementation](https://github.com/arkivanov/TetrisLite)
