package com.yet.bitmessage.feature.chats.conversations.contacts.store

import com.app.domain.model.Contact
import com.arkivanov.mvikotlin.core.store.Store

internal interface ContactsStore :
    Store<ContactsStore.Intent, ContactsStore.State, ContactsStore.Label> {

    data class State(
        val isLoading: Boolean = true,
        val contacts: List<Contact> = emptyList(),
    )

    sealed interface Intent {
        /** Block or unblock the contact with this Noise key (hex). */
        data class SetBlocked(val noiseKeyHex: String, val blocked: Boolean) : Intent
    }

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class Loaded(val contacts: List<Contact>) : Msg
    }

    sealed interface Label
}
