@file:OptIn(ExperimentalTime::class)

package com.app.data.vouch

import com.app.common.AppDispatchers
import com.app.crypto.EncryptionService
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.database.dao.VouchDao
import com.app.domain.model.Fingerprint
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.VouchRepository
import com.app.transport.mesh.MeshService
import com.app.transport.model.PeerCapabilities
import com.app.transport.model.VouchAttestation
import com.app.transport.vouch.VouchEventListener
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Transitive verification ("vouching"), ported from the reference `ChatVouchCoordinator` +
 * `SecureIdentityStateManager` vouch gates. When a Noise session comes up with a peer I verified, I
 * attest — over that authenticated, encrypted session — to the other identities I have verified.
 * Receivers accept such vouches only from peers *they* verified, giving a serverless
 * verified-by-people-you-verify tier.
 *
 * Platform-free (commonMain): mirrors [VerificationCoordinator][com.app.data.verification.VerificationCoordinator]
 * — depends on the commonMain [MeshService] port, the crypto seam, and stately/kotlinx primitives so
 * no JVM types leak in and it compiles under iosArm64. Attaches itself as
 * [MeshService.vouchEventListener] and also serves the [VouchRepository] read port.
 */
@SingleIn(AppScope::class)
@Inject
class VouchCoordinator(
    private val meshService: MeshService,
    private val identityState: SecureIdentityStateManager,
    private val encryption: EncryptionService,
    private val vouchDao: VouchDao,
    private val identityRepository: IdentityRepository,
    dispatchers: AppDispatchers,
) : VouchEventListener, VouchRepository {

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())

    /** Verified set from the previous emission of the flow, to detect newly-verified fingerprints. */
    private var lastVerified: Set<String> = identityState.getVerifiedFingerprints()

    init {
        meshService.vouchEventListener = this

        // Trigger — local verification completed: when a new fingerprint enters the verified set
        // (QR verify), run a vouch pass over connected verified peers, propagating the newly-verified
        // identity to my other verified peers. Matches the reference `vouchToConnectedVerifiedPeers`.
        identityRepository.observeVerifiedFingerprints()
            .onEach { verified ->
                val current = verified.mapTo(mutableSetOf()) { it.value }
                val added = current - lastVerified
                lastVerified = current
                if (added.isNotEmpty()) vouchToConnectedVerifiedPeers()
            }
            .launchIn(scope)
    }

    // MARK: - Emission

    /** Trigger — Noise session established: attempt a vouch batch to a peer I verified. */
    override fun onPeerAuthenticated(peerID: String, fingerprint: String) {
        scope.launch { attemptVouch(peerID, fingerprint) }
    }

    /** Run a vouch pass over every currently connected peer I verified. */
    private suspend fun vouchToConnectedVerifiedPeers() {
        for (peerID in meshService.connectedPeerIDs()) {
            val fingerprint = meshService.getPeerFingerprint(peerID) ?: continue
            attemptVouch(peerID, fingerprint)
        }
    }

    /**
     * Exchange policy shared by every trigger: to a peer I verified, send attestations for up to
     * [VouchAttestation.MAX_BATCH_COUNT] *other* verified fingerprints (most recently verified
     * first), at most once per peer per [BATCH_INTERVAL_MS]. Returns whether a batch was sent.
     */
    private suspend fun attemptVouch(peerID: String, fingerprint: String, now: Long = nowMs()): Boolean {
        if (!identityState.isVerifiedFingerprint(fingerprint)) return false

        // Capability gate, race-tolerant: a peer's vouch bit rides on its announce, processed
        // independently of the Noise handshake, so at authentication time the set is frequently
        // still empty. Skip only when the peer advertised a non-empty set that lacks vouch.
        val capabilities = meshService.getPeerInfo(peerID)?.capabilities ?: PeerCapabilities.NONE
        if (!capabilities.isEmpty() && !capabilities.contains(PeerCapabilities.VOUCH)) return false

        identityState.lastVouchBatchSentAt(fingerprint)?.let { lastSent ->
            if (now - lastSent < BATCH_INTERVAL_MS) return false
        }

        val candidates = identityState.mostRecentlyVerifiedFingerprints(
            limit = VouchAttestation.MAX_BATCH_COUNT,
            excluding = fingerprint,
        )
        val attestations = candidates.mapNotNull { candidate ->
            val fingerprintData = candidate.hexToBytesOrNull(VouchAttestation.FINGERPRINT_SIZE) ?: return@mapNotNull null
            val signingKey = encryption.announcedSigningKey(candidate) ?: return@mapNotNull null
            if (signingKey.size != VouchAttestation.SIGNING_KEY_SIZE) return@mapNotNull null
            VouchAttestation.build(
                voucheeFingerprint = fingerprintData,
                voucheeSigningKey = signingKey,
                timestampMs = now.toULong(),
                sign = encryption::signData,
            )
        }

        if (attestations.isEmpty()) return false
        val payload = VouchAttestation.encodeList(attestations) ?: return false
        meshService.sendVouchAttestations(payload, peerID)
        identityState.markVouchBatchSent(fingerprint, now)
        return true
    }

    // MARK: - Accept

    /**
     * Accept policy: process inbound vouches only from a sender I verified, only with a valid
     * Ed25519 signature under the sender's announce-bound signing key, and only within the validity
     * window. Self-vouches and vouches for already-verified peers are dropped by the storage gates.
     */
    override fun onVouchAttestations(peerID: String, payload: ByteArray, timestampMs: Long) {
        scope.launch {
            val senderFingerprint = meshService.getPeerFingerprint(peerID) ?: return@launch
            if (!identityState.isVerifiedFingerprint(senderFingerprint)) return@launch
            val senderSigningKey = encryption.announcedSigningKey(senderFingerprint) ?: return@launch

            val now = nowMs()
            for (attestation in VouchAttestation.decodeList(payload)) {
                val signatureValid = attestation.verifySignature(senderSigningKey) { key, data, sig ->
                    encryption.verifyEd25519Signature(sig, data, key)
                }
                if (!signatureValid || attestation.isExpired(now)) continue
                recordVouch(attestation.voucheeFingerprintHex, senderFingerprint, attestation.timestampMs.toLong(), now)
            }
        }
    }

    /**
     * Storage gates evaluated against the verified set (the DAO enforces only the per-vouchee cap):
     * no self-vouch, the voucher must be verified-by-me, the vouchee must not already be verified,
     * and the attestation must be inside its validity window.
     */
    private suspend fun recordVouch(
        voucheeFingerprint: String,
        voucherFingerprint: String,
        timestampMs: Long,
        now: Long,
    ): Boolean {
        if (voucheeFingerprint == voucherFingerprint) return false
        val verified = identityState.getVerifiedFingerprints()
        if (voucherFingerprint !in verified || voucheeFingerprint in verified) return false
        val age = now - timestampMs
        if (age > VouchAttestation.MAX_AGE_MS || age < -VouchAttestation.MAX_CLOCK_SKEW_MS) return false
        return vouchDao.record(voucheeFingerprint, voucherFingerprint, timestampMs)
    }

    // MARK: - VouchRepository (read)

    override suspend fun isVouched(fingerprint: Fingerprint): Boolean {
        if (identityState.isVerifiedFingerprint(fingerprint.value)) return false
        return validVouchers(fingerprint).isNotEmpty()
    }

    override suspend fun validVouchers(fingerprint: Fingerprint): List<Fingerprint> {
        val verified = identityState.getVerifiedFingerprints()
        val now = nowMs()
        return vouchDao.vouchersFor(fingerprint.value)
            .asSequence()
            .filter { it.voucherFingerprint != fingerprint.value }
            .filter { it.voucherFingerprint in verified }
            .filter { now - it.timestampMs <= VouchAttestation.MAX_AGE_MS }
            .map { Fingerprint(it.voucherFingerprint) }
            .toList()
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun String.hexToBytesOrNull(expectedSize: Int): ByteArray? {
        if (length != expectedSize * 2) return null
        return ByteArray(expectedSize) { i ->
            substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
        }
    }

    private companion object {
        /** Minimum spacing between vouch batches to the same peer (persisted). 24h. */
        const val BATCH_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
