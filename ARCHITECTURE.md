# BitChat Architecture

## Project Structure

The BitChat project follows a clean separation between **navigation/business logic** (feature components) and **UI presentation** (screens).

### Directory Structure

```
app/src/main/java/com/bitchat/android/
├── feature/                    # Navigation & Business Logic (Decompose Components)
│   ├── root/
│   │   ├── RootComponent.kt
│   │   └── DefaultRootComponent.kt
│   ├── onboarding/
│   │   ├── OnboardingComponent.kt
│   │   └── DefaultOnboardingComponent.kt
│   └── chat/
│       ├── ChatComponent.kt
│       └── DefaultChatComponent.kt
│
├── ui/                         # UI Presentation Layer
│   ├── screens/
│   │   ├── root/
│   │   │   └── RootContent.kt
│   │   ├── onboarding/
│   │   │   └── OnboardingFlowScreen.kt
│   │   └── chat/
│   │       └── ChatScreen.kt
│   ├── ChatHeader.kt
│   ├── MessageComponents.kt
│   ├── InputComponents.kt
│   └── ... (other UI components)
│
├── onboarding/                 # Onboarding utilities & managers
│   ├── PermissionManager.kt
│   ├── OnboardingCoordinator.kt
│   ├── BluetoothStatusManager.kt
│   ├── LocationStatusManager.kt
│   └── BatteryOptimizationManager.kt
│
└── MainActivity.kt             # App entry point
```

## Architecture Principles

### 1. **Feature Components** (`feature/`)
- **Purpose**: Navigation logic, state management, and business rules
- **Technology**: Decompose components with MVIKotlin stores (planned)
- **Responsibilities**:
  - Define navigation structure
  - Handle user actions/intents
  - Manage component lifecycle
  - Coordinate between different parts of the app

**Example:**
```kotlin
// feature/root/RootComponent.kt
interface RootComponent {
    val childStack: Value<ChildStack<*, Child>>
    
    sealed class Child {
        data class Onboarding(val component: OnboardingComponent) : Child()
        data class Chat(val component: ChatComponent) : Child()
    }
}
```

### 2. **UI Screens** (`ui/screens/`)
- **Purpose**: Pure UI presentation
- **Technology**: Jetpack Compose
- **Responsibilities**:
  - Render UI based on component state
  - Handle user interactions (delegate to components)
  - No business logic

**Example:**
```kotlin
// ui/screens/root/RootContent.kt
@Composable
fun RootContent(component: RootComponent, ...) {
    Children(stack = component.childStack) {
        when (val child = it.instance) {
            is RootComponent.Child.Onboarding -> OnboardingFlowScreen(...)
            is RootComponent.Child.Chat -> ChatScreen(...)
        }
    }
}
```

### 3. **Separation of Concerns**

| Layer | Location | Responsibility |
|-------|----------|----------------|
| **Navigation** | `feature/` | Component hierarchy, navigation stack, routing |
| **Business Logic** | `feature/` + ViewModels | State management, data processing, use cases |
| **UI Presentation** | `ui/screens/` | Composables, UI state rendering |
| **Utilities** | `onboarding/`, `nostr/`, etc. | Managers, helpers, platform-specific code |

## Navigation Flow

```
MainActivity
    └── RootContent (ui/screens/root/)
            └── RootComponent (feature/root/)
                    ├── OnboardingComponent (feature/onboarding/)
                    │       └── OnboardingFlowScreen (ui/screens/onboarding/)
                    │
                    └── ChatComponent (feature/chat/)
                            └── ChatScreen (ui/screens/chat/)
```

## Migration Status

### ✅ Completed
- [x] Restructured directories (`feature/` and `ui/screens/`)
- [x] Moved `RootComponent` and `DefaultRootComponent` to `feature/root/`
- [x] Moved `OnboardingComponent` and `DefaultOnboardingComponent` to `feature/onboarding/`
- [x] Moved `ChatComponent` and `DefaultChatComponent` to `feature/chat/`
- [x] Moved `RootContent` to `ui/screens/root/`
- [x] Moved `OnboardingFlowScreen` to `ui/screens/onboarding/`
- [x] Moved `ChatScreen` to `ui/screens/chat/`
- [x] Updated all package declarations and imports
- [x] Verified build success

### 🔄 In Progress
- [ ] Integrate MVIKotlin stores within Decompose components
- [ ] Create feature modules for `home`, `game`, `history`, `settings` (if applicable)
- [ ] Refactor `ChatViewModel` to use MVIKotlin store pattern
- [ ] Address `ChatViewModel.ensureGeohashDMSubscriptionIfNeeded` reflection issue

### 📋 Planned
- [ ] Extract more UI components from `ChatScreen` into separate files
- [ ] Create integration layer (mappers) between stores and components
- [ ] Add unit tests for components and stores
- [ ] Document component contracts and state flows

## Benefits of This Structure

1. **Clear Separation**: Navigation logic is separate from UI rendering
2. **Testability**: Components can be tested independently of UI
3. **Reusability**: UI screens can be reused with different component implementations
4. **Scalability**: Easy to add new features as separate modules
5. **Maintainability**: Changes to navigation don't affect UI and vice versa
6. **Type Safety**: Decompose provides compile-time navigation safety

## Key Files

- **`MainActivity.kt`**: App entry point, creates root component
- **`feature/root/DefaultRootComponent.kt`**: Manages app-level navigation
- **`ui/screens/root/RootContent.kt`**: Renders the navigation stack
- **`feature/onboarding/DefaultOnboardingComponent.kt`**: Onboarding flow logic
- **`ui/screens/onboarding/OnboardingFlowScreen.kt`**: Onboarding UI
- **`feature/chat/DefaultChatComponent.kt`**: Chat navigation (placeholder)
- **`ui/screens/chat/ChatScreen.kt`**: Main chat interface

## Next Steps

1. **Integrate MVIKotlin**: Replace direct ViewModel usage with MVIKotlin stores in components
2. **Create Stores**: Define `RootStore`, `OnboardingStore`, `ChatStore` with proper state/intent/label patterns
3. **Add Mappers**: Create integration layer to map store states to component models
4. **Refactor ChatViewModel**: Break down into smaller, focused stores
5. **Add Tests**: Write unit tests for stores and component logic
