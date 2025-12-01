# MVI Migration: ChatViewModel/ChatState → Store

## Goal
Replace MVVM pattern (ChatViewModel + ChatState) with MVI pattern (Store.State as single source of truth).

## Current State (Final)

### ✅ Fully Migrated
- **ChatStoreFactory**: 0 ChatViewModel usages (was 55)
- **MeshPeerListStoreFactory**: 0 ChatViewModel usages (was 20)
- **LocationChannelsStoreFactory**: 0 ChatViewModel usages
- **DebugStoreFactory**: 0 ChatViewModel usages
- **MeshEventBus**: Implements `BluetoothMeshDelegate`, exposes SharedFlows
- **MediaSendingManager**: Refactored to use callbacks, injectable via Koin
- **GeohashRepository**: Exposes `teleportedGeo` StateFlow directly

### 📊 Migration Progress
| Component | Before | After | Status |
|-----------|--------|-------|--------|
| ChatStoreFactory | 55 | 0 | ✅ Complete |
| MeshPeerListStoreFactory | 20 | 0 | ✅ Complete |
| LocationChannelsStoreFactory | 0 | 0 | ✅ Complete |
| DebugStoreFactory | 0 | 0 | ✅ Complete |

### 🔄 Remaining (Intentional Bridge)
- **DefaultRootComponent**: 10 ChatViewModel usages in `CompositeBluetoothMeshDelegate`
  - This is intentional - forwards mesh events to both MeshEventBus (MVI) and ChatViewModel (legacy managers)
  - Can be removed once all legacy managers are migrated

## Architecture (Current)

```
BluetoothMeshService
        │
        ▼ delegate
CompositeBluetoothMeshDelegate
        │
   ┌────┴────┐
   ▼         ▼
MeshEventBus  ChatViewModel (legacy managers only)
   │
   ▼ SharedFlows
ChatStore ◄─────────────────────┐
   │                            │
   │ State                      │ Services
   ▼                            │
DefaultChatComponent            │
   │                            │
   ├─► MeshPeerListStore ◄──────┤ (subscribes to parent)
   │                            │
   ▼ Model                      │
ChatScreen                      │
                                │
LocationChannelManager ─────────┤
GeohashViewModel ───────────────┤
  └─► GeohashRepository         │
       └─► teleportedGeo flow   │
TorManager ─────────────────────┤
PoWPreferenceManager ───────────┤
DataManager ────────────────────┤
MediaSendingManager ────────────┘
```

## Key Migrations Completed

1. **MeshEventBus as delegate**: Created `CompositeBluetoothMeshDelegate` to forward events to both MVI and legacy systems

2. **State management**: ChatStore now manages all state directly:
   - Messages, channels, private chats
   - Peer info (nicknames, RSSI, fingerprints, session states)
   - Favorites, bookmarks
   - Command/mention suggestions

3. **Services used directly**:
   - `DataManager` for persistence
   - `BluetoothMeshService` for mesh operations
   - `LocationChannelManager` for location channels
   - `GeohashViewModel` for geohash features
   - `MediaSendingManager` for file transfers

4. **Child stores**: MeshPeerListStore subscribes to parent ChatStore state via `stateFlow` extension

## Next Steps (Optional)

To fully remove ChatViewModel:
1. Migrate remaining legacy managers (MessageManager, ChannelManager, PrivateChatManager)
2. Update CompositeBluetoothMeshDelegate to only use MeshEventBus
3. Remove ChatViewModel from DefaultRootComponent
4. Delete ChatViewModel.kt and ChatState.kt

## Key Principle

**Store.State is the single source of truth.** Services dispatch events → Store receives via flows → Reducer updates State → UI observes State.
