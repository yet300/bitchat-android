# BitChat Clean Architecture Roadmap

## Current State Analysis

### Problem
The codebase has mixed architectural patterns causing tight coupling:
- UI layer (`ui/`) directly depends on `ChatViewModel` and legacy managers
- Legacy managers (`ChatState`, `ChannelManager`, `PrivateChatManager`, etc.) mutate shared state
- Nostr handlers directly mutate `ChatState` instead of emitting events
- Feature layer stores partially migrated but still bridge to legacy code

### Current Layer Dependencies (Problematic)
```
┌─────────────────────────────────────────────────────────────┐
│  UI Layer (ui/screens/)                                     │
│  - ChatScreen, sheets, dialogs                              │
│  - Directly uses ChatViewModel in some places               │
└─────────────────────┬───────────────────────────────────────┘
                      │ ❌ Mixed dependencies
                      ▼
┌─────────────────────────────────────────────────────────────┐
│  Feature Layer (feature/)                                   │
│  - Components (ChatComponent, etc.)                         │
│  - Stores (ChatStore, etc.)                                 │
│  - Still bridges to ChatViewModel/legacy managers           │
└─────────────────────┬───────────────────────────────────────┘
                      │ ❌ Circular dependencies
                      ▼
┌─────────────────────────────────────────────────────────────┐
│  Legacy Layer (ui/)                                         │
│  - ChatViewModel, ChatState                                 │
│  - MessageManager, ChannelManager, PrivateChatManager       │
│  - GeohashViewModel, MeshDelegateHandler                    │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│  Services Layer (mesh/, nostr/, services/)                  │
│  - BluetoothMeshService, NostrTransport                     │
│  - GeohashRepository, MessageRouter                         │
│  - Handlers mutate ChatState directly ❌                    │
└─────────────────────────────────────────────────────────────┘
```

## Target Architecture (Onion/Clean Architecture)

### Principles
1. **Dependency Rule**: Inner layers don't know about outer layers
2. **UI is dumb**: Only observes state and dispatches intents
3. **Single Source of Truth**: Store.State is the only state
4. **Event-Driven**: Services emit events, Stores consume them

### Target Layer Dependencies
```
┌─────────────────────────────────────────────────────────────┐
│  UI Layer (ui/screens/)                                     │
│  - Pure Compose functions                                   │
│  - Only knows about Component interface                     │
│  - Observes Component.model (Value<Model>)                  │
│  - Calls Component methods for user actions                 │
└─────────────────────┬───────────────────────────────────────┘
                      │ ✅ Depends only on Component interface
                      ▼
┌─────────────────────────────────────────────────────────────┐
│  Feature Layer (feature/)                                   │
│  - Component: Navigation + child management                 │
│  - Store: State management + business logic                 │
│  - Maps Store.State → Component.Model                       │
│  - Subscribes to service StateFlows                         │
└─────────────────────┬───────────────────────────────────────┘
                      │ ✅ Depends on Domain interfaces
                      ▼
┌─────────────────────────────────────────────────────────────┐
│  Domain Layer (domain/ or within services)                  │
│  - Repository interfaces                                    │
│  - Use cases (optional)                                     │
│  - Domain models                                            │
└─────────────────────┬───────────────────────────────────────┘
                      │ ✅ No external dependencies
                      ▼
┌─────────────────────────────────────────────────────────────┐
│  Infrastructure Layer (mesh/, nostr/, services/)            │
│  - Repository implementations                               │
│  - Network services                                         │
│  - Emit events via SharedFlow/StateFlow                     │
│  - Never mutate UI state directly                           │
└─────────────────────────────────────────────────────────────┘
```


## Key Components in Target Architecture

### 1. UI Layer (`ui/screens/`)
```kotlin
// ChatScreen.kt - Pure UI, no business logic
@Composable
fun ChatScreen(component: ChatComponent) {
    val model by component.model.subscribeAsState()
    
    // UI only reads from model
    MessagesList(messages = model.messages)
    
    // UI only calls component methods
    Button(onClick = { component.onSendMessage(text) })
}
```

### 2. Feature Layer (`feature/`)

#### Component Interface
```kotlin
// ChatComponent.kt - Contract for UI
interface ChatComponent {
    val model: Value<Model>  // Observable state for UI
    
    // User actions
    fun onSendMessage(content: String)
    fun onJoinChannel(channel: String)
    // ...
    
    data class Model(
        val messages: List<BitchatMessage>,
        val nickname: String,
        val isConnected: Boolean,
        // ... all UI-relevant state
    )
}
```

#### Store (MVIKotlin)
```kotlin
// ChatStore.kt - State container
interface ChatStore : Store<Intent, State, Label> {
    data class State(/* all state */)
    sealed class Intent { /* user actions */ }
    sealed class Label { /* one-time events */ }
}

// ChatStoreFactory.kt - Business logic
class ChatStoreFactory {
    // Subscribes to service flows
    // Handles intents
    // Updates state
    // NO direct UI dependencies
}
```

### 3. Domain Layer

#### Event Bus Pattern
```kotlin
// ChatEventBus.kt - Decouples services from stores
@Singleton
class ChatEventBus {
    // Messages
    private val _messageReceived = MutableSharedFlow<BitchatMessage>()
    val messageReceived: SharedFlow<BitchatMessage> = _messageReceived
    
    // Delivery status
    private val _deliveryAck = MutableSharedFlow<DeliveryAckEvent>()
    val deliveryAck: SharedFlow<DeliveryAckEvent> = _deliveryAck
    
    // Private messages
    private val _privateMessageReceived = MutableSharedFlow<PrivateMessageEvent>()
    val privateMessageReceived: SharedFlow<PrivateMessageEvent> = _privateMessageReceived
    
    // Peer updates
    private val _peerListUpdated = MutableSharedFlow<List<String>>()
    val peerListUpdated: SharedFlow<List<String>> = _peerListUpdated
    
    // Emit methods for services
    suspend fun emitMessage(message: BitchatMessage) = _messageReceived.emit(message)
    suspend fun emitDeliveryAck(event: DeliveryAckEvent) = _deliveryAck.emit(event)
    // ...
}
```

### 4. Infrastructure Layer

#### Services emit events, don't mutate state
```kotlin
// NostrDirectMessageHandler.kt - AFTER refactor
class NostrDirectMessageHandler(
    private val chatEventBus: ChatEventBus,  // ✅ Emit events
    private val repo: GeohashRepository,
    // NO ChatState, NO PrivateChatManager ❌
) {
    fun onGiftWrap(giftWrap: NostrEvent, ...) {
        // Process message
        val message = BitchatMessage(...)
        
        // Emit event - Store will handle state update
        chatEventBus.emitPrivateMessage(PrivateMessageEvent(convKey, message))
    }
}
```

## Migration Path

### Phase 1: Event Bus Infrastructure ✅ Complete
- [x] `MeshEventBus` exists for mesh events
- [x] `ChatEventBus` created as pure domain object (no DI annotations)
- [x] Covers: public messages, channel messages, private messages, delivery status, geohash participants

### Phase 2: Refactor Nostr Handlers ✅ Complete
- [x] `GeohashMessageHandler` → emits to `ChatEventBus`
- [x] `NostrDirectMessageHandler` → emits to `ChatEventBus`
- [x] Removed `ChatState` dependency from handlers
- [x] Handlers now use `nicknameProvider` callback instead of direct state access

### Phase 3: ChatStore as Single Source of Truth ✅ Complete
- [x] `ChatStore` subscribes to `MeshEventBus` and `ChatEventBus`
- [x] `ChatStoreFactory` subscribes to service flows directly (renamed from `subscribeToViewModelFlows`)
- [x] No ViewModel bridge code in ChatStore
- [x] `ChatStoreFactory` handles all state mutations

### Phase 4: Remove Legacy Code
- [ ] Delete `ChatViewModel.kt`
- [ ] Delete `ChatState.kt`
- [ ] Delete `MessageManager.kt` (logic moves to Store)
- [ ] Delete `ChannelManager.kt` (logic moves to Store)
- [ ] Delete `PrivateChatManager.kt` (logic moves to Store)
- [ ] Delete `MeshDelegateHandler.kt` (logic moves to Store)
- [ ] Delete `GeohashViewModel.kt` (logic moves to Store)
- [ ] Delete `CommandProcessor.kt` (logic moves to Store)

### Phase 5: Clean Up UI Layer
- [ ] Remove any remaining ViewModel references
- [ ] UI only uses Component interface
- [ ] All state comes from `Component.Model`

## File Structure After Migration

```
app/src/main/java/com/bitchat/android/
├── feature/                    # Feature layer (Components + Stores)
│   ├── chat/
│   │   ├── ChatComponent.kt           # Interface
│   │   ├── DefaultChatComponent.kt    # Implementation
│   │   └── store/
│   │       ├── ChatStore.kt           # Store interface
│   │       └── ChatStoreFactory.kt    # Store implementation
│   └── ...
│
├── domain/                     # Domain layer (NEW)
│   ├── event/
│   │   └── ChatEventBus.kt           # Event bus for decoupling
│   ├── model/
│   │   └── BitchatMessage.kt         # Domain models
│   └── repository/
│       └── MessageRepository.kt      # Repository interfaces
│
├── ui/                         # UI layer (Pure Compose)
│   ├── screens/
│   │   └── chat/
│   │       ├── ChatScreen.kt         # Main screen
│   │       ├── sheets/               # Bottom sheets
│   │       └── dialogs/              # Dialogs
│   ├── components/                   # Reusable UI components
│   └── theme/                        # Theme definitions
│
├── infrastructure/             # Infrastructure layer
│   ├── mesh/                         # Bluetooth mesh
│   ├── nostr/                        # Nostr protocol
│   ├── services/                     # App services
│   └── persistence/                  # Local storage
│
└── di/                         # Dependency injection
    └── AppModule.kt
```

## Benefits of Target Architecture

1. **Testability**: Each layer can be tested in isolation
2. **Maintainability**: Clear boundaries, single responsibility
3. **Scalability**: Easy to add new features without touching existing code
4. **Debuggability**: Unidirectional data flow makes state changes traceable
5. **Reusability**: Domain logic independent of UI framework

## Key Decisions

### Why MVIKotlin Stores?
- Enforces unidirectional data flow
- Built-in support for Decompose
- Clear separation of Intent → State → Label
- Easy to test with fake executors

### Why Event Bus over Direct Calls?
- Decouples services from UI state
- Services don't need to know about Stores
- Multiple consumers can react to same event
- Easier to add logging/debugging

### Why Keep Components?
- Navigation management
- Child component lifecycle
- Maps Store.State to UI-friendly Model
- Handles one-time events (Labels)


## Example: Message Flow (Target State)

### Receiving a Mesh Message
```
1. BluetoothMeshService receives BLE data
   ↓
2. MeshEventBus.emitMessage(BitchatMessage)
   ↓
3. ChatStoreFactory.ExecutorImpl subscribes to MeshEventBus.messageReceived
   ↓
4. Executor dispatches ChatStore.Msg.MessageAdded(message)
   ↓
5. Reducer updates ChatStore.State (messages list)
   ↓
6. DefaultChatComponent maps State → Model
   ↓
7. ChatScreen observes model.messages, recomposes
```

### Sending a Message
```
1. User types and clicks send in ChatScreen
   ↓
2. ChatScreen calls component.onSendMessage(content)
   ↓
3. DefaultChatComponent calls store.accept(Intent.SendMessage(content))
   ↓
4. ChatStoreFactory.ExecutorImpl handles intent
   ↓
5. Executor calls meshService.sendMessage() or nostrTransport.send()
   ↓
6. Executor dispatches Msg.MessageAdded (optimistic update)
   ↓
7. Service sends message, emits delivery ack when confirmed
   ↓
8. Executor receives ack, dispatches Msg.DeliveryStatusUpdated
```

### Receiving a Nostr DM
```
1. NostrRelayManager receives gift-wrapped event
   ↓
2. NostrDirectMessageHandler decrypts and validates
   ↓
3. ChatEventBus.emitPrivateMessage(PrivateMessageEvent)
   ↓
4. ChatStoreFactory subscribes, dispatches Msg.PrivateMessageReceived
   ↓
5. Reducer updates State.privateChats
   ↓
6. Component maps to Model, UI recomposes
```

## Current vs Target: Side-by-Side

| Aspect | Current (Legacy) | Target (Clean) |
|--------|------------------|----------------|
| State Location | ChatState (mutable) | ChatStore.State (immutable) |
| State Updates | Direct mutation | Reducer pattern |
| Service → UI | Mutate ChatState | Emit to EventBus |
| UI → Service | Through ViewModel | Through Component → Store |
| Testing | Hard (shared mutable state) | Easy (pure functions) |
| Dependencies | Circular | Unidirectional |

## Implementation Priority

### High Priority (Blocking Issues)
1. **ChatEventBus** - Unified event bus for all message types
2. **Refactor NostrDirectMessageHandler** - Remove ChatState dependency
3. **Refactor GeohashMessageHandler** - Remove ChatState dependency

### Medium Priority (Code Quality)
4. **Remove ChatViewModel** - All logic in ChatStore
5. **Remove legacy managers** - Logic absorbed by Store
6. **Clean up DI** - Remove legacy class registrations

### Low Priority (Polish)
7. **Move models to domain/** - Better organization
8. **Add repository interfaces** - Full clean architecture
9. **Documentation** - Update architecture docs

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Breaking existing functionality | Incremental migration, keep legacy working |
| Performance regression | Profile before/after, optimize hot paths |
| Increased complexity | Clear documentation, consistent patterns |
| Team learning curve | Pair programming, code reviews |

## Success Criteria

- [ ] No UI code imports from `ui/` package except screens/components
- [ ] No service code imports `ChatState` or legacy managers
- [ ] All state flows through Store → Component → UI
- [ ] Unit tests for Store reducers and executors
- [ ] Build succeeds with legacy files deleted
