package com.yet.bitmessage.feature.chats.conversations.boards

/** Transient overlay for composing a new board post. */
sealed interface BoardDialog {
    data object Create : BoardDialog
}
