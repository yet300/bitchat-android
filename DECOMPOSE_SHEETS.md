# Decompose Sheet Navigation Implementation

## Summary

Successfully migrated sheet navigation in ChatScreen from local state management to Decompose's slot navigation, following the pattern from the Tetris reference implementation.

## Changes Made

### 1. **ChatComponent Interface** (`feature/chat/ChatComponent.kt`)
- Added `sheetSlot: Value<ChildSlot<*, SheetChild>>` for Decompose navigation
- Added sheet control methods:
  - `onDismissSheet()`
  - `onShowAppInfo()`
  - `onShowLocationChannels()`
  - `onShowLocationNotes()`
  - `onShowUserSheet(nickname: String, messageId: String?)`
- Defined `SheetChild` sealed interface with:
  - `AppInfo`
  - `LocationChannels`
  - `LocationNotes`
  - `UserSheet(nickname, messageId)`

### 2. **DefaultChatComponent** (`feature/chat/DefaultChatComponent.kt`)
- Implemented `SlotNavigation<SheetConfig>` for sheet management
- Created `childSlot` with serializable configuration
- Implemented all sheet control methods using `activate()` and `dismiss()`
- Added `@Serializable` `SheetConfig` sealed interface matching `SheetChild`

### 3. **ChatScreen** (`ui/screens/chat/ChatScreen.kt`)
- **Updated signature**: Added `component: ChatComponent` parameter
- **Removed local state**: Deleted `showLocationChannelsSheet`, `showLocationNotesSheet`, `showUserSheet`, `selectedUserForSheet`, `selectedMessageForSheet`
- **Updated callbacks**:
  - `onShowAppInfo` → `component.onShowAppInfo()`
  - `onLocationChannelsClick` → `component.onShowLocationChannels()`
  - `onLocationNotesClick` → `component.onShowLocationNotes()`
  - `onMessageLongPress` → `component.onShowUserSheet(nickname, messageId)`
- **Replaced `ChatDialogs`** with new `ChatSheets` composable
- **Created `ChatSheets`**:
  - Observes `component.sheetSlot` using `subscribeAsState()`
  - Renders appropriate sheet based on `SheetChild` type
  - Uses `component::onDismissSheet` for all dismissals

### 4. **RootContent** (`ui/screens/root/RootContent.kt`)
- Updated `ChatScreen` call to pass `component` parameter

## Architecture Benefits

### Before (Local State)
```kotlin
var showLocationChannelsSheet by remember { mutableStateOf(false) }
var showUserSheet by remember { mutableStateOf(false) }
var selectedUserForSheet by remember { mutableStateOf("") }

// In callback
onLocationChannelsClick = { showLocationChannelsSheet = true }
onUserClick = { user ->
    selectedUserForSheet = user
    showUserSheet = true
}

// In UI
if (showLocationChannelsSheet) {
    LocationChannelsSheet(...)
}
```

### After (Decompose Navigation)
```kotlin
// In component
override fun onShowLocationChannels() {
    sheetNavigation.activate(SheetConfig.LocationChannels)
}

// In callback
onLocationChannelsClick = { component.onShowLocationChannels() }

// In UI
val sheetSlot by component.sheetSlot.subscribeAsState()
sheetSlot.child?.instance?.let { child ->
    when (child) {
        is SheetChild.LocationChannels -> LocationChannelsSheet(...)
    }
}
```

## Key Improvements

1. **Single Source of Truth**: Sheet state managed by Decompose, not scattered local state
2. **Type Safety**: Compile-time guarantees for navigation
3. **Testability**: Component methods can be tested independently
4. **State Preservation**: Decompose handles configuration changes automatically
5. **Back Button**: Automatic back button handling with `handleBackButton = true`
6. **Serialization**: Sheet state can be saved/restored across process death

## Pattern Matching Reference

This implementation follows the exact pattern from the Tetris example:

```kotlin
// Tetris HomeComponent
val childBottomSheetNavigation: Value<ChildSlot<*, BottomSheetChild>>

sealed interface BottomSheetChild {
    data class SettingsChild(val component: SettingsComponent) : BottomSheetChild
    data class HistoryChild(val component: HistoryComponent) : BottomSheetChild
}

// BitChat ChatComponent (equivalent)
val sheetSlot: Value<ChildSlot<*, SheetChild>>

sealed interface SheetChild {
    data object AppInfo : SheetChild
    data object LocationChannels : SheetChild
    data class UserSheet(val nickname: String, val messageId: String?) : SheetChild
}
```

## Remaining Work

- [ ] Migrate `showAppInfo` from ViewModel to Decompose (currently still using `viewModel.showAppInfo()`)
- [ ] Consider migrating password dialog to Decompose navigation
- [ ] Add more sheet types as needed (e.g., debug settings could be a separate sheet)
- [ ] Implement deep linking support for sheets if needed

## Testing Checklist

- [x] Build succeeds
- [ ] App info sheet opens and closes correctly
- [ ] Location channels sheet opens and closes correctly
- [ ] Location notes sheet opens and closes correctly
- [ ] User sheet opens with correct nickname and message
- [ ] Back button dismisses sheets
- [ ] Multiple sheets can be opened in sequence
- [ ] Sheet state survives configuration changes

## Files Modified

1. `/app/src/main/java/com/bitchat/android/feature/chat/ChatComponent.kt`
2. `/app/src/main/java/com/bitchat/android/feature/chat/DefaultChatComponent.kt`
3. `/app/src/main/java/com/bitchat/android/ui/screens/chat/ChatScreen.kt`
4. `/app/src/main/java/com/bitchat/android/ui/screens/root/RootContent.kt`

## Build Status

✅ **BUILD SUCCESSFUL** - All changes compile without errors
