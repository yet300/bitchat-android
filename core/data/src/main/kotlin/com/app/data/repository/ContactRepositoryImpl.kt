@file:OptIn(ExperimentalTime::class)

package com.app.data.repository

import com.app.common.encoding.dataFromHexString
import com.app.common.encoding.hexEncodedString
import com.app.crypto.identity.PeerFingerprintManager
import com.app.data.favorites.FavoritesPersistenceService
import com.app.database.Contact as ContactEntity
import com.app.database.dao.ContactDao
import com.app.domain.model.Contact
import com.app.domain.model.Fingerprint
import com.app.domain.model.PeerId
import com.app.domain.model.PeerIdentity
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.IdentityRepository
import com.app.data.routing.PeerAddressResolver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Favorites/blocking by fingerprint, now in the encrypted DB ([ContactDao]) instead of plaintext
 * prefs, plus the Noise<->Nostr bridge via [FavoritesPersistenceService] (already encrypted, kept).
 *
 * The favorite/blocked sets are stored as flags on the `contact` row keyed by fingerprint. Block state
 * is consulted synchronously on the incoming-message hot path ([isBlocked]), so a mirror of the blocked
 * fingerprints is kept in memory — hydrated from the DB on start and written through on every change.
 */
@SingleIn(AppScope::class)
@Inject
internal class ContactRepositoryImpl(
    private val contactDao: ContactDao,
    private val favorites: FavoritesPersistenceService,
    private val fingerprints: PeerFingerprintManager,
    private val peerAddressResolver: PeerAddressResolver,
    private val identityRepository: IdentityRepository,
    private val scope: CoroutineScope,
) : ContactRepository {

    private val blockedMirror = MutableStateFlow<Set<String>>(emptySet())

    init {
        scope.launch { contactDao.observeBlocked().collect { blockedMirror.value = it.toSet() } }
    }

    override fun observeFavorites(): Flow<Set<Fingerprint>> =
        contactDao.observeFavorites().map { rows -> rows.mapTo(mutableSetOf()) { Fingerprint(it.fingerprint) } }

    override fun observeContacts(): Flow<List<Contact>> =
        // Re-emit when favorites or blocked change; the relationship details (nickname, Nostr key,
        // mutuality) come from the favorites store, annotated here with blocked + verified state.
        combine(
            contactDao.observeFavorites(),
            contactDao.observeBlocked(),
            identityRepository.observeVerifiedFingerprints(),
        ) { _, blockedFps, verified ->
            val blocked = blockedFps.toSet()
            favorites.getOurFavorites().map { rel ->
                val fingerprint = fingerprintOf(rel.peerNoisePublicKey)
                Contact(
                    identity = PeerIdentity(rel.peerNoisePublicKey.hexEncodedString(), rel.peerNostrPublicKey),
                    nickname = rel.peerNickname,
                    isFavorite = rel.isFavorite,
                    theyFavoritedUs = rel.theyFavoritedUs,
                    favoritedAt = rel.favoritedAt,
                    lastUpdated = rel.lastUpdated,
                    isBlocked = fingerprint in blocked,
                    isVerified = Fingerprint(fingerprint) in verified,
                )
            }
        }

    override fun observeVerified(noiseKeyHex: String): Flow<Boolean> {
        val fingerprint = noiseKeyHex.dataFromHexString()?.let { Fingerprint(fingerprintOf(it)) }
        return identityRepository.observeVerifiedFingerprints().map { verified ->
            fingerprint != null && fingerprint in verified
        }
    }

    override suspend fun toggleFavorite(peer: PeerId) {
        val fp = fingerprintFor(peer) ?: return
        val existing = contactDao.byFingerprint(fp)
        val nowFavorite = existing?.is_favorite != 1L
        val resolved = peerAddressResolver.resolveNoiseIdentity(peer.raw)
        contactDao.upsert(
            rowOf(fp, resolved?.first?.hexEncodedString(), favorite = nowFavorite, blocked = existing?.is_blocked == 1L, existing),
        )
        // Persist the relationship into the favorites store too: observeContacts, the Nostr-pubkey
        // resolution and mutual-favorite offline reachability all read from there.
        resolved?.let { (noiseKey, nickname) -> favorites.updateFavoriteStatus(noiseKey, nickname, nowFavorite) }
    }

    override suspend fun isFavorite(peer: PeerId): Boolean {
        val fp = fingerprintFor(peer) ?: return false
        return contactDao.byFingerprint(fp)?.is_favorite == 1L
    }

    override suspend fun setBlocked(peer: PeerId, blocked: Boolean) {
        val fp = fingerprintFor(peer) ?: return
        val existing = contactDao.byFingerprint(fp)
        contactDao.upsert(
            rowOf(fp, noiseKeyHexFor(peer) ?: existing?.noise_key_hex, favorite = existing?.is_favorite == 1L, blocked = blocked, existing),
        )
        // Write through so the synchronous hot-path check sees the change immediately.
        blockedMirror.value = if (blocked) blockedMirror.value + fp else blockedMirror.value - fp
    }

    override fun isBlocked(peer: PeerId): Boolean {
        val fp = fingerprintFor(peer) ?: return false
        return fp in blockedMirror.value
    }

    override suspend fun contact(identity: PeerIdentity): Contact? {
        val noiseKey = identity.noiseKeyHex.dataFromHexString() ?: return null
        val rel = favorites.getFavoriteStatus(noiseKey) ?: return null
        return Contact(
            identity = PeerIdentity(identity.noiseKeyHex, rel.peerNostrPublicKey),
            nickname = rel.peerNickname,
            isFavorite = rel.isFavorite,
            theyFavoritedUs = rel.theyFavoritedUs,
            favoritedAt = rel.favoritedAt,
            lastUpdated = rel.lastUpdated,
        )
    }

    override suspend fun noiseKeyHexForNostrAlias(alias: PeerId): String? =
        peerAddressResolver.noiseKeyHexForNostrAlias(alias.raw)

    override suspend fun clearAll() {
        contactDao.deleteAll()
        favorites.clearAllFavorites()
        blockedMirror.value = emptySet()
    }

    /** Build a full `contact` row, preserving fields not being changed. */
    private fun rowOf(fp: String, noiseKeyHex: String?, favorite: Boolean, blocked: Boolean, existing: ContactEntity?): ContactEntity =
        ContactEntity(
            fingerprint = fp,
            noise_key_hex = noiseKeyHex ?: existing?.noise_key_hex,
            nostr_pubkey = existing?.nostr_pubkey,
            nickname = existing?.nickname,
            is_favorite = if (favorite) 1L else 0L,
            they_favorited_us = existing?.they_favorited_us ?: 0L,
            favorited_at = existing?.favorited_at ?: 0L,
            last_updated = Clock.System.now().toEpochMilliseconds(),
            is_blocked = if (blocked) 1L else 0L,
        )

    /** Noise key hex for a peer when derivable: a 64-hex stable id is itself the key; else resolve. */
    private fun noiseKeyHexFor(peer: PeerId): String? =
        if (peer.kind == PeerId.Kind.NOISE_STABLE) peer.raw
        else peerAddressResolver.resolveNoiseIdentity(peer.raw)?.first?.hexEncodedString()

    /**
     * Resolve a peer to its stored fingerprint (SHA-256 hex of the Noise static key). Prefers the live
     * mesh mapping; for an offline 64-hex Noise key it derives the fingerprint directly.
     */
    private fun fingerprintFor(peer: PeerId): String? {
        fingerprints.getFingerprintForPeer(peer.raw)?.let { return it }
        if (peer.kind == PeerId.Kind.NOISE_STABLE) {
            val noiseKey = peer.raw.dataFromHexString() ?: return null
            return runCatching {
                MessageDigest.getInstance("SHA-256").digest(noiseKey).hexEncodedString()
            }.getOrNull()
        }
        return null
    }

    /** SHA-256 hex fingerprint of a raw Noise static public key (same scheme as [fingerprintFor]). */
    private fun fingerprintOf(noiseKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(noiseKey).hexEncodedString()
}
