package com.app.domain.model

/** Aggregated search results across messages, contacts and channels. */
data class SearchResults(
    val messages: List<BitMessage>,
    val contacts: List<Contact>,
    val channels: List<Channel>,
) {
    val isEmpty: Boolean
        get() = messages.isEmpty() && contacts.isEmpty() && channels.isEmpty()

    companion object {
        val EMPTY = SearchResults(emptyList(), emptyList(), emptyList())
    }
}
