package com.yet.bitmessage.feature.chats.conversations.settings

/**
 * Settings dialogs hosted in a [ChildSlot][com.arkivanov.decompose.router.slot.ChildSlot].
 * Lightweight (no business logic of their own — the store performs the action).
 */
sealed interface SettingsDialog {
    /** Confirm the irreversible panic wipe. */
    data object PanicConfirm : SettingsDialog

    /** Show this device's verification QR (rendered from the store's loaded matrix). */
    data object MyQr : SettingsDialog
}
