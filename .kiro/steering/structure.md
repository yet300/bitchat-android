# Project Structure

```
app/src/main/java/com/bitchat/android/
├── feature/                    # Decompose components (navigation + business logic)
│   ├── root/                   # App root navigation
│   ├── onboarding/             # Onboarding flow
│   ├── chat/                   # Chat feature with sub-components
│   │   ├── locationchannels/   # Geohash channel selection
│   │   ├── locationnotes/      # Location-based notes
│   │   ├── meshpeerlist/       # Mesh peer list
│   │   ├── passwordprompt/     # Channel password entry
│   │   └── usersheet/          # User profile sheet
│   └── about/                  # About screen
│
├── ui/                         # UI presentation layer (Compose)
│   ├── screens/                # Full-screen composables
│   │   ├── root/               # RootContent.kt
│   │   ├── onboarding/         # OnboardingFlowScreen.kt
│   │   └── chat/               # ChatScreen.kt, dialogs/, sheets/
│   ├── components/             # Reusable UI components
│   ├── media/                  # Media-related UI (images, audio, files)
│   ├── theme/                  # Theme, typography, colors
│   └── debug/                  # Debug settings UI
│
├── core/                       # Core utilities
│   ├── common/                 # Shared utilities (coroutineScope, asValue)
│   ├── data/                   # Data layer implementations
│   ├── domain/                 # Domain models and repository interfaces
│   └── ui/                     # Core UI components
│
├── mesh/                       # Bluetooth mesh networking
├── nostr/                      # Nostr protocol implementation
├── noise/                      # Noise protocol encryption
├── crypto/                     # Encryption services
├── protocol/                   # Binary protocol encoding
├── sync/                       # Message sync (GCS filters, gossip)
├── services/                   # App services (routing, retention)
├── onboarding/                 # Onboarding managers and screens
├── di/                         # Koin dependency injection
├── net/                        # Network (OkHttp, Tor)
├── geohash/                    # Geohash utilities
├── identity/                   # Identity management
├── model/                      # Data models (packets, messages)
├── util/                       # General utilities
└── features/                   # Feature utilities (file, media, voice)
```

## Architecture Pattern

**Decompose + MVIKotlin**:
- `feature/*/Component.kt` - Interface defining component contract
- `feature/*/DefaultComponent.kt` - Implementation with navigation logic
- `feature/*/store/Store.kt` - MVIKotlin store interface (State, Intent, Label)
- `feature/*/store/StoreFactory.kt` - Store implementation with Executor
- `feature/*/integration/Mappers.kt` - State-to-model mappers

**UI Layer**:
- `ui/screens/*/` - Composable screens that observe component state
- Components expose `Value<Model>` for UI to observe
- UI delegates actions back to component methods

## Key Files
- `MainActivity.kt` - App entry, creates root component
- `BitchatApplication.kt` - Application class, Koin initialization
- `ChatViewModel.kt` - Main chat state management (legacy, being migrated)
- `BluetoothMeshService.kt` - Core BLE mesh networking
