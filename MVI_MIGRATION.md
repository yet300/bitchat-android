# MVI Migration: ChatViewModel/ChatState → Store

## Status: ✅ Complete

The MVI migration is **complete**. All legacy ViewModels and state containers have been removed. The feature layer now uses pure MVI architecture with Stores.

> **See [docs/CLEAN_ARCHITECTURE.md](docs/CLEAN_ARCHITECTURE.md) for the full target architecture roadmap.**

## Current State

### Architecture ✅ Clean MVI
- UI layer (`ui/screens/`) uses `ChatComponent` correctly
- `ChatStore` manages all chat state with proper MVI pattern
- `MeshEventBus` handles mesh events
- `ChatEventBus` handles Nostr events
- Feature layer components use services directly (no ViewModels)
- All legacy code has been removed

## Architecture Layers

```
┌─────────────────────────────────────────────────────────────┐
│  UI Layer (ui/screens/) ✅ Clean                            │
│  - Only uses ChatComponent interface                        │
│  - Observes component.model                                 │
└─────────────────────┬───────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────┐
│  Feature Layer (feature/) ✅ Clean MVI                      │
│  - ChatComponent, ChatStore                                 │
│  - Uses services directly (no ViewModel bridge)             │
└─────────────────────┬───────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────┐
│  Domain Layer (domain/) ✅ Clean                            │
│  - ChatEventBus for event-driven communication              │
│  - Pure domain objects (no DI annotations)                  │
└─────────────────────┬───────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────┐
│  Services Layer (mesh/, nostr/, services/) ✅ Clean         │
│  - MeshEventBus for mesh events                             │
│  - GeohashRepository as service                             │
│  - Handlers emit events to ChatEventBus                     │
└─────────────────────────────────────────────────────────────┘
```

## Migration Phases (All Complete)

### Phase 1: Feature Layer ✅ Complete
- [x] ChatStore with MVI pattern
- [x] ChatComponent interface
- [x] UI uses Component, not ViewModel
- [x] MeshEventBus for mesh events

### Phase 2: Event Bus Expansion ✅ Complete
- [x] Created `ChatEventBus` as pure domain object
- [x] `GeohashMessageHandler` emits to ChatEventBus
- [x] `NostrDirectMessageHandler` emits to ChatEventBus
- [x] Handlers no longer depend on `ChatState`

### Phase 3: Remove Legacy Bridge ✅ Complete
- [x] ChatStore subscribes to ChatEventBus
- [x] ChatStore subscribes to service flows directly
- [x] Renamed `subscribeToViewModelFlows` → `subscribeToServiceFlows`

### Phase 4: Delete Legacy Code ✅ Complete
- [x] Deleted ChatViewModel.kt
- [x] Deleted ChatState.kt
- [x] Deleted MessageManager.kt
- [x] Deleted ChannelManager.kt
- [x] Deleted PrivateChatManager.kt
- [x] Deleted MeshDelegateHandler.kt
- [x] Deleted GeohashViewModel.kt
- [x] Deleted CommandProcessor.kt
- [x] Deleted ChatViewModelUtils.kt
- [x] Deleted ConversationAliasResolver.kt
- [x] Moved CommandSuggestion to model package

## Key Files

### Feature Layer
- `feature/chat/ChatComponent.kt` - Component interface
- `feature/chat/DefaultChatComponent.kt` - Implementation
- `feature/chat/store/ChatStore.kt` - Store interface
- `feature/chat/store/ChatStoreFactory.kt` - Store implementation

### Domain Layer
- `domain/event/ChatEventBus.kt` - Event bus for Nostr events

### Services
- `nostr/GeohashRepository.kt` - Geohash participant service
- `nostr/GeohashMessageHandler.kt` - Emits to ChatEventBus
- `nostr/NostrDirectMessageHandler.kt` - Emits to ChatEventBus
- `mesh/MeshEventBus.kt` - Mesh event handling

### Models
- `model/CommandSuggestion.kt` - Command autocomplete data class

## Deleted Files (Legacy)
- `ui/ChatViewModel.kt`
- `ui/ChatState.kt`
- `ui/MessageManager.kt`
- `ui/ChannelManager.kt`
- `ui/PrivateChatManager.kt`
- `ui/MeshDelegateHandler.kt`
- `ui/GeohashViewModel.kt`
- `ui/CommandProcessor.kt`
- `ui/ChatViewModelUtils.kt`
- `services/ConversationAliasResolver.kt`
