package com.yet.bitmessage.feature.chats.conversations.contacts

import com.app.common.decompose.asValue
import com.app.domain.model.ConversationId
import com.app.domain.model.PeerId
import com.app.domain.repository.ContactRepository
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yet.bitmessage.feature.chats.conversations.contacts.store.ContactsStore
import com.yet.bitmessage.feature.chats.conversations.contacts.store.ContactsStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultContactsComponent(
    componentContext: ComponentContext,
    storeFactory: ContactsStoreFactory,
    private val onContactSelected: (ConversationId) -> Unit,
    private val onClose: () -> Unit,
) : ContactsComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }

    override val model: Value<ContactsComponent.Model> = store.asValue().map(contactsStateToModel)

    override fun onContactClicked(noiseKeyHex: String) =
        onContactSelected(ConversationId.Private(PeerId(noiseKeyHex)))

    override fun onToggleBlock(noiseKeyHex: String, blocked: Boolean) =
        store.accept(ContactsStore.Intent.SetBlocked(noiseKeyHex, blocked))

    override fun onCloseClicked() = onClose()
}

@Inject
internal class DefaultContactsComponentFactory(
    private val storeFactory: StoreFactory,
    private val contactRepository: ContactRepository,
) : ContactsComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        onContactSelected: (ConversationId) -> Unit,
        onClose: () -> Unit,
    ): ContactsComponent = DefaultContactsComponent(
        componentContext = componentContext,
        storeFactory = ContactsStoreFactory(storeFactory, contactRepository),
        onContactSelected = onContactSelected,
        onClose = onClose,
    )
}
