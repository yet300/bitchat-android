@file:OptIn(ExperimentalTime::class)

package com.app.data.routing

import com.app.common.serialization.JsonConfig
import com.app.common.utils.Log
import com.app.database.Outbox_envelope
import com.app.database.dao.OutboxDao
import com.app.data.favorites.FavoritesChangeListener
import com.app.data.favorites.FavoritesPersistenceService
import com.app.transport.routing.OutgoingEnvelope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Persistent outbox for envelopes that could not be routed immediately.
 *
 * Previously an in-memory `HashMap`, so every undelivered private message evaporated on app kill.
 * Now backed by the encrypted `outbox_envelope` table ([OutboxDao]) so the queue survives process
 * death and flushes when routing becomes possible again (mutual-favorite mapping, established Noise
 * session). Flush triggers are unchanged — only the storage moved.
 *
 * Semantics preserved from the in-memory version: [drain] removes and returns a peer's whole queue
 * atomically (the DB transaction replaces the old shared lock, closing the same enqueue/drain race,
 * audit A4). Added on top: a 24h expiry (mirrors iOS CourierEnvelope.maxLifetimeSeconds) and a global
 * entry cap, both enforced lazily on enqueue/drain.
 */
@SingleIn(AppScope::class)
@Inject
internal class Outbox(
    private val favoritesService: FavoritesPersistenceService,
    private val outboxDao: OutboxDao,
) : FavoritesChangeListener {

    /**
     * Called by [RouteSelector] during its own initialisation to hook flush-on-reachable.
     * Nullable so Outbox compiles independently of RouteSelector.
     */
    var onFlushNeeded: ((peerID: String) -> Unit)? = null

    /** Envelope time-to-live and total-entry cap (plain params, no UI). */
    var ttlMillis: Long = DEFAULT_TTL_MILLIS
    var maxEntries: Long = DEFAULT_MAX_ENTRIES

    init {
        favoritesService.addListener(this)
    }

    /** Enqueues [envelope] for later delivery. */
    suspend fun enqueue(envelope: OutgoingEnvelope) {
        val now = Clock.System.now().toEpochMilliseconds()
        val dto = envelope.toDto()
        outboxDao.insert(
            peerId = envelope.peerID,
            kind = dto.kind,
            payloadJson = JsonConfig.json.encodeToString(OutboxEnvelopeDto.serializer(), dto),
            messageId = dto.messageId ?: dto.originalMessageId,
            createdAt = now,
            expiryAt = if (ttlMillis > 0) now + ttlMillis else null,
            cap = maxEntries,
        )
    }

    /** Removes and returns all (non-expired) envelopes queued for [peerID], oldest-first. */
    suspend fun drain(peerID: String): List<OutgoingEnvelope> {
        val now = Clock.System.now().toEpochMilliseconds()
        return outboxDao.drainByPeer(peerID, now).mapNotNull { it.toEnvelope() }
    }

    // FavoritesChangeListener -------------------------------------------------

    override fun onFavoriteChanged(noiseKeyHex: String) {
        // A new Noise<->Nostr mapping appeared; try to flush any pending envelopes.
        onFlushNeeded?.invoke(noiseKeyHex)
        // Also try the 16-hex short peerID (commonly used in mesh addressing).
        onFlushNeeded?.invoke(noiseKeyHex.take(16))
    }

    override fun onAllCleared() {
        // Leave queued envelopes intact — routing may still become possible later.
    }

    // Envelope <-> row mapping ------------------------------------------------

    private fun OutgoingEnvelope.toDto(): OutboxEnvelopeDto = when (this) {
        is OutgoingEnvelope.Private -> OutboxEnvelopeDto(
            kind = KIND_PRIVATE, peerID = peerID, content = content,
            recipientNickname = recipientNickname, messageId = messageId,
        )
        is OutgoingEnvelope.Receipt -> OutboxEnvelopeDto(
            kind = KIND_RECEIPT, peerID = peerID, originalMessageId = originalMessageId,
        )
        is OutgoingEnvelope.Ack -> OutboxEnvelopeDto(
            kind = KIND_ACK, peerID = peerID, messageId = messageId,
        )
        is OutgoingEnvelope.Favorite -> OutboxEnvelopeDto(
            kind = KIND_FAVORITE, peerID = peerID, isFavorite = isFavorite,
        )
    }

    private fun Outbox_envelope.toEnvelope(): OutgoingEnvelope? {
        val dto = runCatching {
            JsonConfig.json.decodeFromString(OutboxEnvelopeDto.serializer(), payload_json)
        }.getOrNull() ?: run {
            Log.e(TAG, "Dropping un-decodable outbox row id=$id kind=$kind")
            return null
        }
        return when (dto.kind) {
            KIND_PRIVATE -> OutgoingEnvelope.Private(
                peerID = dto.peerID,
                content = dto.content.orEmpty(),
                recipientNickname = dto.recipientNickname.orEmpty(),
                messageId = dto.messageId.orEmpty(),
            )
            KIND_RECEIPT -> OutgoingEnvelope.Receipt(
                peerID = dto.peerID, originalMessageId = dto.originalMessageId.orEmpty(),
            )
            KIND_ACK -> OutgoingEnvelope.Ack(peerID = dto.peerID, messageId = dto.messageId.orEmpty())
            KIND_FAVORITE -> OutgoingEnvelope.Favorite(
                peerID = dto.peerID, isFavorite = dto.isFavorite ?: false,
            )
            else -> {
                Log.e(TAG, "Dropping outbox row with unknown kind=${dto.kind}")
                null
            }
        }
    }

    /**
     * Serializable projection of [OutgoingEnvelope] for `payload_json`. [OutgoingEnvelope] itself lives
     * in the transport module and is not @Serializable, so the mapping stays here in the data layer.
     */
    @Serializable
    internal data class OutboxEnvelopeDto(
        val kind: String,
        val peerID: String,
        val content: String? = null,
        val recipientNickname: String? = null,
        val messageId: String? = null,
        val originalMessageId: String? = null,
        val isFavorite: Boolean? = null,
    )

    private companion object {
        const val TAG = "Outbox"

        const val KIND_PRIVATE = "private"
        const val KIND_RECEIPT = "receipt"
        const val KIND_ACK = "ack"
        const val KIND_FAVORITE = "favorite"

        const val DEFAULT_TTL_MILLIS = 24L * 60 * 60 * 1000 // 24h (iOS CourierEnvelope.maxLifetimeSeconds)
        const val DEFAULT_MAX_ENTRIES = 500L
    }
}
