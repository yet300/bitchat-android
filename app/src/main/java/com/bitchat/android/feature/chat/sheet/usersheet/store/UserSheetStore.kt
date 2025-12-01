package com.bitchat.android.feature.chat.sheet.usersheet.store

import com.arkivanov.mvikotlin.core.store.Store
import com.bitchat.android.model.BitchatMessage

interface UserSheetStore : Store<UserSheetStore.Intent, UserSheetStore.State, UserSheetStore.Label> {

    sealed interface Intent {
        data object Slap : Intent
        data object Hug : Intent
        data object Block : Intent
    }

    data class State(
        val targetNickname: String,
        val selectedMessage: BitchatMessage?,
        val isSelf: Boolean,
        val isGeohashChannel: Boolean
    )

    sealed interface Action {
        data object Init : Action
    }

    sealed interface Msg {
        // No state changes needed for now as everything is static for the sheet's lifetime
    }

    sealed interface Label {
        data object Dismiss : Label
    }
}
