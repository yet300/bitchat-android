package com.yet.bitmessage.feature.chats.conversations.contacts

import com.app.domain.model.ConversationId
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Contacts screen (D4): the people we favorited, split into active favorites and a blocked list,
 * with per-contact actions. Opening a DM is navigation, owned by the parent (like list selection).
 * Verifying a contact (QR) is a follow-up slice.
 */
interface ContactsComponent {

    val model: Value<Model>

    /** Open a direct message with the contact. */
    fun onContactClicked(noiseKeyHex: String)

    fun onToggleBlock(noiseKeyHex: String, blocked: Boolean)

    fun onCloseClicked()

    data class Model(
        val isLoading: Boolean,
        val favorites: List<ContactRow>,
        val blocked: List<ContactRow>,
    )

    data class ContactRow(
        val name: String,
        val noiseKeyHex: String,
        val isMutual: Boolean,
        val isBlocked: Boolean,
    )

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            onContactSelected: (ConversationId) -> Unit,
            onClose: () -> Unit,
        ): ContactsComponent
    }
}
