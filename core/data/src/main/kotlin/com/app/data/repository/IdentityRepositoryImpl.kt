package com.app.data.repository

import android.content.Context
import com.app.crypto.EncryptionService
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.domain.model.Fingerprint
import com.app.domain.model.MyIdentity
import com.app.domain.model.PeerId
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.SettingsStore
import com.app.transport.nostr.NostrIdentityBridge
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Our own identity plus peer-verification state. The Noise fingerprint comes from the crypto layer
 * ([EncryptionService] — same source the mesh derives `myPeerID` from), verified fingerprints live in
 * the secure store ([SecureIdentityStateManager]), and the Nostr npub is derived on demand. The QR /
 * Noise challenge-response that *produces* a verification is infrastructure and stays outside.
 */
@SingleIn(AppScope::class)
@Inject
internal class IdentityRepositoryImpl(
    private val encryption: EncryptionService,
    private val identityState: SecureIdentityStateManager,
    private val settings: SettingsStore,
    private val context: Context,
) : IdentityRepository {

    private val verifiedState = MutableStateFlow(loadVerified())

    override suspend fun myIdentity(): MyIdentity {
        val fingerprint = encryption.getIdentityFingerprint()
        return MyIdentity(
            peerId = PeerId(fingerprint.take(PEER_ID_HEX_LENGTH)),
            fingerprint = Fingerprint(fingerprint),
            nickname = settings.getStringOrNull(KEY_NICKNAME).orEmpty(),
            nostrNpub = runCatching {
                NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub
            }.getOrNull(),
        )
    }

    override fun observeVerifiedFingerprints(): Flow<Set<Fingerprint>> = verifiedState.asStateFlow()

    override suspend fun isVerified(fingerprint: Fingerprint): Boolean =
        identityState.isVerifiedFingerprint(fingerprint.value)

    override suspend fun setVerified(fingerprint: Fingerprint, verified: Boolean) {
        identityState.setVerifiedFingerprint(fingerprint.value, verified)
        verifiedState.value = loadVerified()
    }

    override suspend fun panicWipe() {
        // Wipe the persisted secure store (static keys, verified set, Nostr keys, caches) and the
        // in-memory geohash-identity cache. Mesh recreation / media / bookmarks are app-lifecycle
        // orchestration handled elsewhere; the use-case clears messages/contacts separately.
        identityState.clearIdentityData()
        runCatching { NostrIdentityBridge.clearAllAssociations(context) }
        verifiedState.value = emptySet()
    }

    private fun loadVerified(): Set<Fingerprint> =
        identityState.getVerifiedFingerprints().mapTo(mutableSetOf(), ::Fingerprint)

    private companion object {
        const val KEY_NICKNAME = "nickname"
        const val PEER_ID_HEX_LENGTH = 16
    }
}
