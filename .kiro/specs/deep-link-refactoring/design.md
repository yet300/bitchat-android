# Design Document: Deep Link Refactoring

## Overview

This design refactors BitChat's deep link handling to follow Decompose best practices. The implementation will use Decompose's `handleDeepLink` extension function, proper kotlinx-serialization support, and a consolidated type hierarchy for deep link data.

## Architecture

The deep link flow follows Decompose's recommended pattern:

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   Intent/URI    │────▶│   MainActivity   │────▶│  RootComponent  │
│  (Notification) │     │  handleDeepLink  │     │   navigation    │
└─────────────────┘     └──────────────────┘     └─────────────────┘
                                                          │
                                                          ▼
                                                 ┌─────────────────┐
                                                 │  ChatComponent  │
                                                 │  (with config)  │
                                                 └─────────────────┘
```

### Deep Link Processing Flow

1. **Initial Launch**: `handleDeepLink` extracts URI, creates `DefaultRootComponent` with `initialDeepLink`
2. **Runtime Navigation**: `onNewIntent` extracts deep link, calls `root.onDeepLink()`
3. **Pending Deep Links**: If app not initialized, deep link is stored and processed after onboarding

## Components and Interfaces

### DeepLinkData (Refactored)

```kotlin
// feature/root/DeepLinkData.kt
@Serializable
sealed interface DeepLinkData {
    @Serializable
    data object None : DeepLinkData
    
    @Serializable
    data class PrivateChat(val peerID: String) : DeepLinkData
    
    @Serializable
    data class GeohashChat(val geohash: String) : DeepLinkData
}
```

### DeepLinkExtractor (Updated)

```kotlin
// feature/root/DeepLinkExtractor.kt
object DeepLinkExtractor {
    fun fromIntent(intent: Intent): DeepLinkData? {
        // Extract from notification extras
        val isPrivateChat = intent.getBooleanExtra(EXTRA_OPEN_PRIVATE_CHAT, false)
        val isGeohashChat = intent.getBooleanExtra(EXTRA_OPEN_GEOHASH_CHAT, false)
        
        return when {
            isPrivateChat -> {
                val peerID = intent.getStringExtra(EXTRA_PEER_ID) ?: return null
                DeepLinkData.PrivateChat(peerID)
            }
            isGeohashChat -> {
                val geohash = intent.getStringExtra(EXTRA_GEOHASH) ?: return null
                DeepLinkData.GeohashChat(geohash)
            }
            else -> null
        }
    }
    
    // Future: fromUri(uri: Uri): DeepLinkData?
}
```

### RootComponent Interface (Updated)

```kotlin
interface RootComponent {
    val childStack: Value<ChildStack<*, Child>>
    val model: Value<Model>
    
    fun onDeepLink(deepLink: DeepLinkData)
    
    // ... rest unchanged
}
```

### DefaultRootComponent (Updated)

```kotlin
class DefaultRootComponent(
    componentContext: ComponentContext,
    // ... other params
    initialDeepLink: DeepLinkData? = null
) : RootComponent, ComponentContext by componentContext {
    
    private var pendingDeepLink: DeepLinkData? = initialDeepLink
    
    // Config now uses DeepLinkData directly (already @Serializable)
    @Serializable
    private sealed interface Config {
        @Serializable
        data object Onboarding : Config
        
        @Serializable
        data class Chat(val deepLink: DeepLinkData = DeepLinkData.None) : Config
    }
}
```

### MainActivity (Updated with handleDeepLink)

```kotlin
class MainActivity : OrientationAwareActivity() {
    private lateinit var root: RootComponent
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Set up ActivityResultLaunchers...
        
        root = handleDeepLink { uri ->
            // Extract deep link from intent (notification extras take precedence)
            val deepLink = DeepLinkExtractor.fromIntent(intent)
            
            DefaultRootComponent(
                componentContext = defaultComponentContext(
                    discardSavedState = deepLink != null
                ),
                // ... other params
                initialDeepLink = deepLink
            )
        } ?: return
        
        setContent { /* ... */ }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DeepLinkExtractor.fromIntent(intent)?.let { deepLink ->
            root.onDeepLink(deepLink)
        }
    }
}
```

## Data Models

### DeepLinkData Sealed Interface

| Variant | Fields | Description |
|---------|--------|-------------|
| `None` | - | No deep link, default navigation |
| `PrivateChat` | `peerID: String` | Navigate to private chat with peer |
| `GeohashChat` | `geohash: String` | Navigate to geohash location channel |

### ChatStartupConfig Mapping

The `ChatComponent.ChatStartupConfig` will be derived from `DeepLinkData`:

```kotlin
fun DeepLinkData.toChatStartupConfig(): ChatComponent.ChatStartupConfig = when (this) {
    DeepLinkData.None -> ChatComponent.ChatStartupConfig.Default
    is DeepLinkData.PrivateChat -> ChatComponent.ChatStartupConfig.PrivateChat(peerID)
    is DeepLinkData.GeohashChat -> ChatComponent.ChatStartupConfig.GeohashChat(geohash)
}
```



## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system-essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Deep link to navigation config preservation

*For any* valid DeepLinkData (PrivateChat with any peerID, or GeohashChat with any geohash), when converted to a navigation Config and then to ChatStartupConfig, the original identifying data (peerID or geohash) SHALL be preserved exactly.

**Validates: Requirements 1.1, 1.2, 5.1**

### Property 2: DeepLinkData serialization round-trip

*For any* DeepLinkData instance (None, PrivateChat, or GeohashChat), serializing to JSON and deserializing back SHALL produce an equivalent object.

**Validates: Requirements 2.1, 2.2**

### Property 3: DeepLinkData to ChatStartupConfig mapping consistency

*For any* DeepLinkData instance, the `toChatStartupConfig()` mapping function SHALL produce a ChatStartupConfig with matching type and data:
- `DeepLinkData.None` → `ChatStartupConfig.Default`
- `DeepLinkData.PrivateChat(peerID)` → `ChatStartupConfig.PrivateChat(peerID)` with same peerID
- `DeepLinkData.GeohashChat(geohash)` → `ChatStartupConfig.GeohashChat(geohash)` with same geohash

**Validates: Requirements 4.2**

## Error Handling

| Scenario | Handling |
|----------|----------|
| Intent missing required extras | `DeepLinkExtractor.fromIntent()` returns `null` |
| Invalid peerID format | Accept any non-null string (validation happens in ChatComponent) |
| Invalid geohash format | Accept any non-null string (validation happens in location services) |
| Deep link before app initialized | Store in `pendingDeepLink`, process after initialization |
| handleDeepLink returns null | Activity finishes gracefully (Decompose handles this) |

## Testing Strategy

### Property-Based Testing

The implementation will use **Kotest** with its property-based testing support for Kotlin.

Each property-based test MUST:
- Run a minimum of 100 iterations
- Be tagged with a comment referencing the correctness property: `**Feature: deep-link-refactoring, Property {number}: {property_text}**`
- Use generators that produce valid DeepLinkData instances

### Unit Tests

Unit tests will cover:
- `DeepLinkExtractor.fromIntent()` with various intent configurations
- Edge cases: null extras, missing required fields
- Integration between DeepLinkData and navigation Config

### Test Structure

```
app/src/test/java/com/bitchat/android/feature/root/
├── DeepLinkDataTest.kt           # Property tests for serialization
├── DeepLinkExtractorTest.kt      # Unit tests for intent extraction
└── DeepLinkMappingTest.kt        # Property tests for config mapping
```
