package com.yet.bitmessage.feature.chats.conversations.contacts.store

import com.app.domain.model.PeerId
import com.app.domain.repository.ContactRepository
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch

internal class ContactsStoreFactory(
    private val storeFactory: StoreFactory,
    private val contactRepository: ContactRepository,
) {
    fun create(): ContactsStore =
        object : ContactsStore,
            Store<ContactsStore.Intent, ContactsStore.State, ContactsStore.Label> by storeFactory.create(
                name = "ContactsStore",
                initialState = ContactsStore.State(),
                bootstrapper = SimpleBootstrapper(ContactsStore.Action.Subscribe),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<ContactsStore.State, ContactsStore.Msg> {
        override fun ContactsStore.State.reduce(msg: ContactsStore.Msg): ContactsStore.State =
            when (msg) {
                is ContactsStore.Msg.Loaded -> copy(isLoading = false, contacts = msg.contacts)
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<ContactsStore.Intent, ContactsStore.Action, ContactsStore.State, ContactsStore.Msg, ContactsStore.Label>() {

        override fun executeAction(action: ContactsStore.Action) {
            when (action) {
                ContactsStore.Action.Subscribe -> scope.launch {
                    contactRepository.observeContacts().collect { dispatch(ContactsStore.Msg.Loaded(it)) }
                }
            }
        }

        override fun executeIntent(intent: ContactsStore.Intent) {
            when (intent) {
                is ContactsStore.Intent.SetBlocked -> scope.launch {
                    contactRepository.setBlocked(PeerId(intent.noiseKeyHex), intent.blocked)
                }
            }
        }
    }
}
