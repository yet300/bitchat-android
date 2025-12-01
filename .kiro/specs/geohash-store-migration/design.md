# Design Document: GeohashViewModel to Store Migration

## Overview

This design migrates geohash functionality from GeohashViewModel (MVVM pattern) to Store-based architecture (MVI pattern). The migration eliminates the last ViewModel dependency from the feature layer, achieving pure MVI architecture.

## Architecture

### Current Architecture (Before Migration)

```
ChatStoreFactory → GeohashViewModel → GeohashRepository → ChatState
                                    ↓
                          NostrSubscriptionManager
```

**Problems:**
- GeohashViewModel is a ViewModel in the feature layer (violates MVI)
- ChatState is mutable state outside Store (violates single source of truth)
- Circular dependency: Store → ViewModel → State

### Target Architecture (After Migration)

```
ChatStore → GeohashRepository (service) → StateFlow<Data>
         ↓
   NostrSubscriptionManager (service)
         ↓
   NostrTransport (service)
```

**Benefits:**
- Pure MVI: All state in Store.State
- Clear layering: Services → Store → UI
- No ViewModels in feature layer
- Single source of truth: Store.State

## Components and Interfaces

### 1. GeohashRepository (Refactored)

**Current:** Takes ChatState as constructor parameter, mutates it directly
**Target:** Service that exposes StateFlows, no mutable state

```kotlin
class GeohashRepository(
    private val applicationContext: Context,
    private val dataManager: DataManager
) {
    // Exposed state
    val geohashPeople: StateFlow<List<GeoPerson>>
    val geohashParticipantCounts: StateFlow<Map<String, Int>>
    val currentGeohash: StateFlow<String?>
    
    // Operations
    fun updateParticipant(geohash: String, pubkey: String, timestamp: Date)
    fun refreshGeohashPeople()
    fun setCurrentGeohash(geohash: String?)
    fun markTeleported(pubkey: String)
    fun isPersonTeleported(pubkey: String): Boolean
    fun findPubkeyByNickname(nickname: String): String?
    fun displayNameForNostrPubkeyUI(pubkey: String): String
}
```

### 2. GeohashMessageHandler (New)

**Purpose:** Handle incoming Nostr geohash messages
**Current:** Embedded in GeohashViewModel
**Target:** Standalone service

```kotlin
class GeohashMessageHandler(
    private val repository: GeohashRepository,
    private val dataManager: DataManager,
    private val powPreferenceManager: PoWPreferenceManager,
    private val onMessageReceived: (String, BitchatMessage) -> Unit
) {
    fun onEvent(event: NostrEvent, geohash: String)
}
```

### 3. ChatStore (Enhanced)

**Add to State:**
```kotlin
data class State(
    // ... existing fields ...
    val currentGeohash: String? = null,
    val geohashPeople: List<GeoPerson> = emptyList(),
    val geohashParticipantCounts: Map<String, Int> = emptyMap()
)
```

**Add Intents:**
```kotlin
sealed interface Intent {
    // ... existing intents ...
    data class SendGeohashMessage(val content: String, val channel: GeohashChannel) : Intent
    data class StartGeohashDM(val nostrPubkey: String) : Intent
    data class BlockUserInGeohash(val nickname: String) : Intent
    data class BeginGeohashSampling(val geohashes: List<String>) : Intent
    object EndGeohashSampling : Intent
}
```

### 4. LocationChannelsStore (Enhanced)

**Remove:** GeohashViewModel injection
**Add:** Direct GeohashRepository subscription

## Data Models

### GeoPerson (Existing)
```kotlin
data class GeoPerson(
    val pubkeyHex: String,
    val displayName: String,
    val lastSeen: Date,
    val isTeleported: Boolean
)
```

### GeohashChannel (Existing)
```kotlin
data class GeohashChannel(
    val level: GeohashChannelLevel,
    val geohash: String
)
```

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system-essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: State consistency after GeohashRepository updates

*For any* geohash participant update, when GeohashRepository updates its internal state, then ChatStore SHALL receive the update via StateFlow and update Store.State accordingly

**Validates: Requirements 1.2, 1.3**

### Property 2: Message sending equivalence

*For any* geohash message content and channel, sending via ChatStore SHALL produce the same Nostr event as sending via GeohashViewModel (before migration)

**Validates: Requirements 1.4**

### Property 3: DM initiation equivalence

*For any* Nostr public key, starting a geohash DM via ChatStore SHALL create the same conversation key and subscription as GeohashViewModel (before migration)

**Validates: Requirements 1.5**

### Property 4: Repository independence

*For any* Store subscribing to GeohashRepository, the repository SHALL provide data without depending on which Store is subscribing

**Validates: Requirements 3.4**

### Property 5: Feature parity

*For any* geohash operation (send message, start DM, block user, sampling), the behavior after migration SHALL be functionally equivalent to before migration

**Validates: Requirements 4.3**

## Error Handling

### GeohashRepository Errors
- **Nostr connection failures**: Log error, continue with cached data
- **Invalid geohash format**: Validate input, reject invalid geohashes
- **Participant data corruption**: Clear corrupted data, rebuild from Nostr events

### ChatStore Geohash Operations
- **Send message failure**: Update message delivery status to failed
- **Subscription failure**: Retry with exponential backoff
- **Invalid pubkey**: Show error label, don't crash

## Testing Strategy

### Unit Tests
- GeohashRepository state updates
- GeohashMessageHandler event processing
- ChatStore geohash intent handling
- NostrSubscriptionManager subscription lifecycle

### Property-Based Tests
- Property 1: Repository update propagation (generate random participant updates)
- Property 2: Message sending equivalence (generate random message content)
- Property 3: DM initiation equivalence (generate random pubkeys)
- Property 5: Feature parity (generate random operation sequences)

### Integration Tests
- End-to-end geohash message flow
- Multi-store GeohashRepository subscription
- Nostr subscription lifecycle with real relay

### Migration Validation
- Run app before and after migration
- Verify all geohash features work identically
- Check no GeohashViewModel references remain
- Verify build succeeds after GeohashViewModel deletion
