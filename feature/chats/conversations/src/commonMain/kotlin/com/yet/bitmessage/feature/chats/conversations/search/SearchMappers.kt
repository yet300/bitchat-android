package com.yet.bitmessage.feature.chats.conversations.search

import com.app.domain.model.ConversationId
import com.app.domain.model.PeerId
import com.yet.bitmessage.feature.chats.conversations.search.store.SearchStore

internal val searchStateToModel: (SearchStore.State) -> SearchComponent.Model = { state ->
    // In-range peers first; then contacts that aren't already represented by a live peer.
    val peopleFromPeers = state.matchingPeers.map { peer ->
        SearchComponent.PersonResult(
            name = peer.nickname.ifBlank { peer.id.raw.take(8) },
            conversationId = ConversationId.Private(peer.id),
            isOnline = true,
        )
    }
    val peerIds = peopleFromPeers.mapTo(mutableSetOf()) { it.conversationId }
    val peopleFromContacts = state.contacts.map { contact ->
        SearchComponent.PersonResult(
            name = contact.nickname,
            conversationId = ConversationId.Private(PeerId(contact.identity.noiseKeyHex)),
            isOnline = false,
        )
    }.filter { it.conversationId !in peerIds }

    SearchComponent.Model(
        query = state.query,
        tab = state.tab,
        isActive = state.isActive,
        chats = state.chats,
        people = peopleFromPeers + peopleFromContacts,
        messages = state.messages,
        channels = state.channels,
    )
}
