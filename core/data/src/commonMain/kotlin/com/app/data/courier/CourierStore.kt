@file:OptIn(ExperimentalTime::class)

package com.app.data.courier

import com.app.database.Courier_envelope
import com.app.database.dao.CourierDao
import com.app.database.dao.CourierTier
import com.app.transport.model.CourierEnvelope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Carried-mail store for the courier role: opaque [CourierEnvelope]s this device holds for offline
 * third parties. Thin domain wrapper over [CourierDao] — maps rows to envelopes and computes the
 * rotating recipient tags — while the DAO owns the resource bounds (quotas, eviction, idempotency).
 * Distinct from the send-outbox and from `StoreForwardManager`; see docs/COURIER_RESEARCH.md.
 *
 * Platform-free (commonMain), mirroring the reference iOS `CourierStore`.
 */
@SingleIn(AppScope::class)
@Inject
class CourierStore(
    private val courierDao: CourierDao,
) {

    /** Per-envelope cooldown between speculative multi-hop handovers (plain param, no UI). */
    var remoteHandoverCooldownMs: Long = DEFAULT_REMOTE_HANDOVER_COOLDOWN_MS

    /**
     * Accept an envelope from a depositor. Validates the envelope shape and rejects an expiry beyond
     * the policy lifetime (a depositor cannot pin storage longer than the outbox would retain the
     * message), then defers the quota/eviction decision to the DAO. Returns false on rejection.
     */
    suspend fun deposit(envelope: CourierEnvelope, depositorNoiseKey: ByteArray, tier: CourierTier): Boolean {
        val now = nowMs()
        if (envelope.recipientTag.size != CourierEnvelope.TAG_LENGTH) return false
        if (envelope.ciphertext.isEmpty() || envelope.ciphertext.size > CourierEnvelope.MAX_CIPHERTEXT_BYTES) return false
        if (envelope.isExpired(now)) return false
        val maxExpiry = now + CourierEnvelope.MAX_LIFETIME_SECONDS * 1000 + MAX_EXPIRY_SLACK_MS
        if (envelope.expiry > maxExpiry.toULong()) return false
        return courierDao.deposit(
            recipientTag = envelope.recipientTag,
            expiry = envelope.expiry.toLong(),
            ciphertext = envelope.ciphertext,
            depositorNoiseKey = depositorNoiseKey,
            storedAt = now,
            tier = tier,
            copies = envelope.copies.toInt(),
            prekeyId = envelope.prekeyID?.toLong(),
            nowMs = now,
        )
    }

    /** Destructive handover: remove and return every envelope addressed to [recipientNoiseKey]. */
    suspend fun takeEnvelopes(recipientNoiseKey: ByteArray): List<CourierEnvelope> {
        val now = nowMs()
        return courierDao.takeByTags(CourierEnvelope.candidateTags(recipientNoiseKey, now), now)
            .map { it.toEnvelope() }
    }

    /**
     * Non-destructive speculative handover toward a recipient heard via a relayed announce. The
     * delivered copy carries no spray budget.
     */
    suspend fun envelopesForRemoteHandover(recipientNoiseKey: ByteArray): List<CourierEnvelope> {
        val now = nowMs()
        return courierDao.remoteHandover(
            CourierEnvelope.candidateTags(recipientNoiseKey, now),
            remoteHandoverCooldownMs,
            now,
        ).map { it.toEnvelope().withCopies(1u) }
    }

    /** Spray copies to hand to another courier just encountered, each with its split budget. */
    suspend fun takeSprayCopies(courierNoiseKey: ByteArray): List<CourierEnvelope> {
        val now = nowMs()
        return courierDao.spray(courierNoiseKey, CourierEnvelope.candidateTags(courierNoiseKey, now), now)
            .map { it.row.toEnvelope().withCopies(it.copies.toUByte()) }
    }

    suspend fun count(): Long = courierDao.count(nowMs())

    suspend fun isEmpty(): Boolean = count() == 0L

    /** Panic wipe: drop all carried mail. */
    suspend fun wipe() = courierDao.wipe()

    private fun Courier_envelope.toEnvelope(): CourierEnvelope = CourierEnvelope(
        recipientTag = recipient_tag,
        expiry = expiry.toULong(),
        ciphertext = ciphertext,
        copies = copies.toUByte(),
        prekeyID = prekey_id?.toUInt(),
    )

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        /** Reference TransportConfig.courierRemoteHandoverCooldownSeconds = 10 min. */
        const val DEFAULT_REMOTE_HANDOVER_COOLDOWN_MS = 10L * 60 * 1000
        /** Slack on top of the 24h lifetime for depositor clock skew (reference maxExpirySlack). */
        const val MAX_EXPIRY_SLACK_MS = 60L * 60 * 1000
    }
}
