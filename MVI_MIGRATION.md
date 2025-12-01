# MVI Migration: ChatViewModel/ChatState → Store

## Status: ✅ Feature Layer Complete

The MVI migration is **complete** for the feature layer. ChatViewModel has been fully eliminated from all Decompose components and MVI Stores.

### Final Migration Results

| Component | ChatViewModel Before | After | Status |
|-----------|---------------------|-------|--------|
| ChatStoreFactory | 1 (teleportedGeo) | 0 | ✅ Complete |
| MeshPeerListStoreFactory | 0 | 0 | ✅ Complete |
| LocationChannelsStoreFactory | 0 | 0 | ✅ Complete |
| DefaultRootComponent | 0 | 0 | ✅ Complete |
| MainActivity | 0 | 0 | ✅ Complete |
| All UI Screens | 0 | 0 | ✅ Complete |

**Total: 0 ChatViewModel dependencies in feature layer**

### Architecture

```
BluetoothMeshService
        │
        ▼ delegate
MeshEventBusDelegate
        │
        ▼
   MeshEventBus
        │
        ▼ SharedFlows
   ChatStore (source of truth)
        │
        ├─► MeshPeerListStore (child, subscribes to parent)
        │
        ▼ Model
   ChatScreen
```

### Key Services Used by ChatStore

- `MeshEventBus` - mesh events via SharedFlows
- `BluetoothMeshService` - mesh operations, peer info
- `DataManager` - persistence (nickname, channels, favorites)
- `GeohashViewModel` - geohash features, teleportedGeo
- `LocationChannelManager` - location channels
- `MediaSendingManager` - file transfers (callback-based)
- `TorManager`, `PoWPreferenceManager` - network settings

### Legacy UI Layer (Not Migrated)

ChatViewModel and ChatState still exist in `app/src/main/java/com/bitchat/android/ui/` for:
- `GeohashViewModel` - uses ChatState for geohash features
- Legacy managers (`MessageManager`, `ChannelManager`, `PrivateChatManager`)
- These are used by GeohashViewModel and other legacy UI code

**This is intentional.** The feature layer (Decompose + MVI) is now pure and doesn't depend on these legacy classes.

### Key Achievements

1. **Pure MVI Architecture**: All feature components use Store pattern
2. **Single Source of Truth**: Store.State is authoritative, no external mutable state
3. **Clean Event Flow**: Services → MeshEventBus → Store → Reducer → State → UI
4. **Child Store Pattern**: MeshPeerListStore subscribes to parent ChatStore state
5. **Callback Pattern**: MediaSendingManager uses callbacks instead of direct state access

### What's Next?

The feature layer migration is complete. Potential future improvements:

1. **Migrate GeohashViewModel** to a Store-based architecture
2. **Remove legacy managers** (MessageManager, ChannelManager, PrivateChatManager) once GeohashViewModel is migrated
3. **Delete ChatState.kt** after all legacy code is migrated
4. **Consider migrating** other ViewModels in the UI layer to MVI pattern

These are optional - the current architecture is clean and maintainable.
