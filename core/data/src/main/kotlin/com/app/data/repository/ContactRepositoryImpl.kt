@file:OptIn(ExperimentalTime::class)

package com.app.data.repository

import com.app.common.encoding.dataFromHexString
import com.app.common.encoding.hexEncodedString
import com.app.common.serialization.JsonConfig
import com.app.crypto.identity.PeerFingerprintManager
import com.app.data.favorites.FavoritesPersistenceService
import com.app.domain.model.Contact
import com.app.domain.model.Fingerprint
import com.app.domain.model.PeerId
import com.app.domain.model.PeerIdentity
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.IdentityRepository
import com.app.common.settings.SettingsStore
import com.app.data.routing.PeerAddressResolver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import java.security.MessageDigest
import kotlin.time.ExperimentalTime

/**
 * Favorites/blocking (by fingerprint, over the shared settings store, same keys as legacy
 * `DataManager`) plus the Noise<->Nostr bridge via [FavoritesPersistenceService].
 */
@SingleIn(AppScope::class)
@Inject
internal class ContactRepositoryImpl(
    private val settings: SettingsStore,
    private val favorites: FavoritesPersistenceService,
    private val fingerprints: PeerFingerprintManager,
    private val peerAddressResolver: PeerAddressResolver,
    private val identityRepository: IdentityRepository,
) : ContactRepository {

    override fun observeFavorites(): Flow<Set<Fingerprint>> =
        settings.getStringOrNullFlow(KEY_FAVORITES).map { json ->
            decodeSet(json).mapTo(mutableSetOf(), ::Fingerprint)
        }

    override fun observeContacts(): Flow<List<Contact>> =
        // Re-emit when any set changes; the relationship details (nickname, Nostr key, mutuality)
        // come from the favorites store, annotated here with blocked + verified state.
        combine(
            settings.getStringOrNullFlow(KEY_FAVORITES),
            settings.getStringOrNullFlow(KEY_BLOCKED),
            identityRepository.observeVerifiedFingerprints(),
        ) { _, blockedJson, verified ->
            val blocked = decodeSet(blockedJson)
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
        val current = loadSet(KEY_FAVORITES)
        saveSet(KEY_FAVORITES, if (fp in current) current - fp else current + fp)
    }

    override suspend fun isFavorite(peer: PeerId): Boolean {
        val fp = fingerprintFor(peer) ?: return false
        return fp in loadSet(KEY_FAVORITES)
    }

    override suspend fun setBlocked(peer: PeerId, blocked: Boolean) {
        val fp = fingerprintFor(peer) ?: return
        val current = loadSet(KEY_BLOCKED)
        saveSet(KEY_BLOCKED, if (blocked) current + fp else current - fp)
    }

    override suspend fun isBlocked(peer: PeerId): Boolean {
        val fp = fingerprintFor(peer) ?: return false
        return fp in loadSet(KEY_BLOCKED)
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
        settings.remove(KEY_FAVORITES)
        settings.remove(KEY_BLOCKED)
        favorites.clearAllFavorites()
    }

    /**
     * Resolve a peer to its stored fingerprint (SHA-256 hex of the Noise static key). Prefers the live
     * mesh mapping; for an offline 64-hex Noise key it derives the fingerprint directly (parity with the
     * legacy `PrivateChatManager.toggleFavorite` fallback).
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

    private fun loadSet(key: String): Set<String> = decodeSet(settings.getStringOrNull(key))

    private fun saveSet(key: String, values: Set<String>) {
        settings.putString(key, JsonConfig.json.encodeToString(SET_SERIALIZER, values))
    }

    private fun decodeSet(json: String?): Set<String> {
        if (json == null) return emptySet()
        return runCatching { JsonConfig.json.decodeFromString(SET_SERIALIZER, json) }.getOrDefault(emptySet())
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_BLOCKED = "blocked_users"
        val SET_SERIALIZER = SetSerializer(String.serializer())
    }
}
