@file:OptIn(ExperimentalSettingsApi::class)

package com.app.data.repository

import com.app.common.serialization.JsonConfig
import com.app.data.AppStateStore
import com.app.data.favorites.FavoritesPersistenceService
import com.app.data.mapper.toDomain
import com.app.domain.model.BitMessage
import com.app.domain.model.Channel
import com.app.domain.model.Contact
import com.app.domain.model.ConversationId
import com.app.domain.model.PeerId
import com.app.domain.repository.SearchRepository
import com.app.transport.model.BitchatMessage
import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Narrow search over the in-memory message timelines ([AppStateStore]), the favorite contacts
 * ([FavoritesPersistenceService]) and the joined channels (settings store). Each source is matched
 * case-insensitively on its obvious field; aggregation/ranking is the search use-case's job.
 */
@SingleIn(AppScope::class)
@Inject
internal class SearchRepositoryImpl(
    private val settings: ObservableSettings,
    private val favorites: FavoritesPersistenceService,
) : SearchRepository {

    override suspend fun searchMessages(query: String): List<BitMessage> {
        val matches = ArrayList<BitMessage>()
        AppStateStore.publicMessages.value
            .filterByContent(query)
            .mapTo(matches) { it.toDomain(ConversationId.PublicMesh, MY_PEER_ID) }
        AppStateStore.privateMessages.value.forEach { (peerId, list) ->
            val id = ConversationId.Private(PeerId(peerId))
            list.filterByContent(query).mapTo(matches) { it.toDomain(id, MY_PEER_ID) }
        }
        AppStateStore.channelMessages.value.forEach { (tag, list) ->
            val id = ConversationId.Channel(tag)
            list.filterByContent(query).mapTo(matches) { it.toDomain(id, MY_PEER_ID) }
        }
        return matches
    }

    override suspend fun searchContacts(query: String): List<Contact> =
        favorites.getOurFavorites()
            .filter { it.peerNickname.contains(query, ignoreCase = true) }
            .map { it.toDomain() }

    override suspend fun searchChannels(query: String): List<Channel> {
        val protected = loadSet(KEY_PROTECTED)
        return loadSet(KEY_JOINED)
            .filter { it.contains(query, ignoreCase = true) }
            .sorted()
            .map { Channel(tag = it, isJoined = true, isPasswordProtected = it in protected) }
    }

    private fun List<BitchatMessage>.filterByContent(query: String): List<BitchatMessage> =
        filter { it.content.contains(query, ignoreCase = true) }

    private fun loadSet(key: String): Set<String> {
        val json = settings.getStringOrNull(key) ?: return emptySet()
        return runCatching { JsonConfig.json.decodeFromString(SET_SERIALIZER, json) }.getOrDefault(emptySet())
    }

    private companion object {
        // Ownership marker for mapped messages; resolving "mine" needs the identity layer's peer id
        // (a later Phase B step), so matches map with a null peer id (isMine == false) for now.
        val MY_PEER_ID: String? = null
        const val KEY_JOINED = "joined_channels"
        const val KEY_PROTECTED = "password_protected_channels"
        val SET_SERIALIZER = SetSerializer(String.serializer())
    }
}
