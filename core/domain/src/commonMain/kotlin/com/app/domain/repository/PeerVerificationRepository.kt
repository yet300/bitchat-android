package com.app.domain.repository

/**
 * Per-contact QR verification (I7b-2b-2). Validates a scanned `bitchat://verify` payload and, when
 * valid, starts the Noise challenge/response against the matching connected peer. The verification
 * *result* (the peer's fingerprint entering the verified set) surfaces via
 * [IdentityRepository.observeVerifiedFingerprints], so callers observe that rather than a return
 * value here.
 */
interface PeerVerificationRepository {

    suspend fun verifyScannedQr(payload: String): VerifyScanResult
}

sealed interface VerifyScanResult {
    /** QR signature valid and fresh; the challenge has been started against [nickname]'s peer. */
    data class Started(val nickname: String) : VerifyScanResult

    /** QR did not parse, the signature was invalid, or it was stale. */
    data object Invalid : VerifyScanResult

    /** QR valid, but the peer it identifies is not currently reachable on the mesh. */
    data object PeerNotFound : VerifyScanResult
}
