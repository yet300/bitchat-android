package com.yet.bitmessage.feature.chats.details.notes.store

import com.app.domain.model.LocationNote
import com.arkivanov.mvikotlin.core.store.Store

internal interface LocationNotesStore :
    Store<LocationNotesStore.Intent, LocationNotesStore.State, Nothing> {

    data class State(
        val notes: List<LocationNote> = emptyList(),
        val isLoading: Boolean = true,
        val draft: String = "",
    ) {
        val canSend: Boolean get() = draft.isNotBlank()
    }

    sealed interface Intent {
        data class DraftChanged(val text: String) : Intent
        data object SendClicked : Intent
    }

    sealed interface Action {
        data object Load : Action
    }

    sealed interface Msg {
        data class NotesLoaded(val notes: List<LocationNote>) : Msg
        data class LoadingChanged(val isLoading: Boolean) : Msg
        data class DraftChanged(val text: String) : Msg
    }
}
