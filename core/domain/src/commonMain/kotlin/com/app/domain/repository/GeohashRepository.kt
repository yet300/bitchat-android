package com.app.domain.repository

import com.app.domain.model.ConversationId
import com.app.domain.model.GeoPerson
import kotlinx.coroutines.flow.Flow

/**
 * Location (geohash) channels and their participants. Nostr identity derivation, presence
 * heartbeats and relay subscriptions are infrastructure and live in the implementation.
 */
interface GeohashRepository {

    /** Participants currently present in the selected geo-channel. */
    fun observeParticipants(): Flow<List<GeoPerson>>

    /** Currently selected public channel — either [ConversationId.PublicMesh] or a Geohash. */
    fun observeSelectedChannel(): Flow<ConversationId>

    /** Live participant counts per geohash (for the channel picker). */
    fun observeParticipantCounts(): Flow<Map<String, Int>>

    suspend fun select(channel: ConversationId)

    /** Open (or resolve) a DM conversation with a geohash participant; returns its id. */
    suspend fun startDirectMessage(pubkeyHex: String): ConversationId

    suspend fun setBlocked(pubkeyHex: String, blocked: Boolean)

    suspend fun isTeleported(pubkeyHex: String): Boolean

    /** Resolve a participant's Nostr pubkey (hex) by display nickname. */
    suspend fun pubkeyForNickname(nickname: String): String?

    /** Resolve a participant's Nostr pubkey (hex) by short id. */
    suspend fun pubkeyForShortId(shortId: String): String?
}
