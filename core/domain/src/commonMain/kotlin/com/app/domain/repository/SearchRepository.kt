package com.app.domain.repository

import com.app.domain.model.BitMessage
import com.app.domain.model.Channel
import com.app.domain.model.Contact

/**
 * Narrow search port (ISP). Each source is queried independently; aggregation lives in the
 * search use-case.
 */
interface SearchRepository {

    suspend fun searchMessages(query: String): List<BitMessage>

    suspend fun searchContacts(query: String): List<Contact>

    suspend fun searchChannels(query: String): List<Channel>
}
