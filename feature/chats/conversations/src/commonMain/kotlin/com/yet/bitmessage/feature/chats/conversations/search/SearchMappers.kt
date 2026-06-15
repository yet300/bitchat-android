package com.yet.bitmessage.feature.chats.conversations.search

import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.yet.bitmessage.feature.chats.conversations.search.store.SearchStore

private const val RECENT_LIMIT = 8

internal val searchStateToModel: (SearchStore.State) -> SearchComponent.Model = { state ->
    // In-range peers first; then contacts that aren't already represented by a live peer.
    val peopleFromPeers = state.matchingPeers.map { it.toPersonResult() }
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
        geo = state.geo,
        nearby = state.onlinePeers.map { it.toPersonResult() },
        recent = state.conversations.take(RECENT_LIMIT),
    )
}

private fun Peer.toPersonResult() = SearchComponent.PersonResult(
    name = nickname.ifBlank { id.raw.take(8) },
    conversationId = ConversationId.Private(id),
    isOnline = true,
)
