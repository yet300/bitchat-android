package com.yet.bitmessage.feature.chats.conversations.search.store

import com.app.domain.model.Channel
import com.app.domain.model.Contact
import com.app.domain.model.Conversation
import com.app.domain.model.GeohashChannel
import com.app.domain.model.Peer
import com.app.domain.model.SearchResults
import com.arkivanov.mvikotlin.core.store.Store
import com.yet.bitmessage.feature.chats.conversations.search.SearchTab

internal interface SearchStore :
    Store<SearchStore.Intent, SearchStore.State, SearchStore.Label> {

    data class State(
        val query: String = "",
        val tab: SearchTab = SearchTab.CHATS,
        val conversations: List<Conversation> = emptyList(),
        val peers: List<Peer> = emptyList(),
        val results: SearchResults = SearchResults.EMPTY,
        val parsedGeo: GeohashChannel? = null,
        val geocodedGeo: GeohashChannel? = null,
        val bookmarks: List<GeohashChannel> = emptyList(),
    ) {
        val isActive: Boolean get() = query.isNotBlank()

        /** Chats tab: client-side filter of the live conversation list (title + last message). */
        val chats: List<Conversation>
            get() = if (!isActive) emptyList() else conversations.filter { it.matches(query) }

        /** People tab: in-range peers matched by nickname, then matching contacts. */
        val matchingPeers: List<Peer>
            get() = if (!isActive) emptyList() else peers.filter { it.nickname.contains(query, ignoreCase = true) }

        val contacts: List<Contact> get() = if (!isActive) emptyList() else results.contacts
        val messages get() = if (!isActive) emptyList() else results.messages
        val channels: List<Channel> get() = if (!isActive) emptyList() else results.channels

        /** Geo tab: a directly-parsed geohash, else a geocoded place name. */
        val geo: GeohashChannel? get() = if (!isActive) null else parsedGeo ?: geocodedGeo

        /** Focused-empty rail: peers currently in range (independent of the query). */
        val onlinePeers: List<Peer> get() = peers
    }

    sealed interface Intent {
        data class QueryChanged(val text: String) : Intent
        data class TabSelected(val tab: SearchTab) : Intent
    }

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class QueryChanged(val text: String, val geo: GeohashChannel?) : Msg
        data class TabSelected(val tab: SearchTab) : Msg
        data class ConversationsLoaded(val conversations: List<Conversation>) : Msg
        data class PeersLoaded(val peers: List<Peer>) : Msg
        data class ResultsLoaded(val results: SearchResults) : Msg
        data class GeocodeLoaded(val geo: GeohashChannel?) : Msg
        data class BookmarksLoaded(val bookmarks: List<GeohashChannel>) : Msg
    }

    sealed interface Label
}

private fun Conversation.matches(query: String): Boolean =
    title.contains(query, ignoreCase = true) ||
        lastMessage?.content?.contains(query, ignoreCase = true) == true
