package com.yet.bitmessage.feature.chats.conversations.contacts

import com.app.domain.model.Contact
import com.yet.bitmessage.feature.chats.conversations.contacts.store.ContactsStore

internal val contactsStateToModel: (ContactsStore.State) -> ContactsComponent.Model = { state ->
    val (blocked, active) = state.contacts.partition { it.isBlocked }
    ContactsComponent.Model(
        isLoading = state.isLoading,
        favorites = active.sortedBy { it.nickname.lowercase() }.map { it.toRow() },
        blocked = blocked.sortedBy { it.nickname.lowercase() }.map { it.toRow() },
    )
}

private fun Contact.toRow() = ContactsComponent.ContactRow(
    name = nickname.ifBlank { identity.noiseKeyHex.take(8) },
    noiseKeyHex = identity.noiseKeyHex,
    isMutual = isMutual,
    isBlocked = isBlocked,
    isVerified = isVerified,
)
