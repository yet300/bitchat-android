# ModalBottomSheet Update

## Summary

Added an animated TopBar with a "Close" button to the shared `ModalBottomSheet` component. This allows sheets to have a consistent header without duplicating code.

## Changes

### `ui/components/ModalBottomSheet.kt`

- Added `LazyListState` creation and management.
- Added `isScrolled` derivation logic.
- Added `topBarAlpha` animation.
- Added a `Box` overlay containing the TopBar with a "Close" button.
- Updated `content` lambda to accept `LazyListState`.

```kotlin
@Composable
fun ModalBottomSheet(
    ...
    content: @Composable ColumnScope.(LazyListState) -> Unit,
) {
    // ... state setup ...

    ModalBottomSheet(...) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                content(listState)
            }

            // TopBar (animated)
            Box(...) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.close_plain))
                }
            }
        }
    }
}
```

## Usage

When using `ModalBottomSheet`, you can now use the provided `LazyListState` for your `LazyColumn` to enable the TopBar background animation (fade in on scroll).

```kotlin
ModalBottomSheet(onDismiss = ...) { listState ->
    LazyColumn(state = listState) {
        // content
    }
}
```

## Note on Existing Sheets

Existing sheets like `AboutSheet` currently manage their own `LazyListState` and TopBar. To fully utilize the shared TopBar and avoid duplication (e.g., double Close buttons), these sheets should be refactored to:
1. Accept `LazyListState` as a parameter.
2. Remove their internal TopBar and Close button.
3. Use the passed `LazyListState` for their `LazyColumn`.

This refactoring can be done incrementally.
