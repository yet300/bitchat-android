package com.app.domain.usecase

import com.app.domain.model.SearchResults
import com.app.domain.repository.SearchRepository

/**
 * Run a query across messages, contacts and channels and aggregate the results. A blank query
 * short-circuits to empty.
 */
class SearchUseCase(
    private val search: SearchRepository,
) {
    suspend operator fun invoke(query: String): SearchResults {
        val q = query.trim()
        if (q.isEmpty()) return SearchResults.EMPTY
        return SearchResults(
            messages = search.searchMessages(q),
            contacts = search.searchContacts(q),
            channels = search.searchChannels(q),
        )
    }
}
