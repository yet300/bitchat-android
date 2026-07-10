package com.app.domain.repository

import com.app.domain.model.Fingerprint

/**
 * Transitive verification ("web of trust"): a peer I verified (the voucher) can attest, over an
 * authenticated Noise session, that they verified another identity (the vouchee). A vouchee I have
 * not personally verified but that at least one of my verified peers vouches for gains the derived
 * `vouched` trust tier — one rung below my own explicit verification.
 *
 * The `vouched` state is never persisted; it is recomputed from stored attestations so that
 * un-verifying a voucher silently invalidates every vouch they gave, with no cascade delete.
 */
interface VouchRepository {

    /**
     * Whether [fingerprint] currently qualifies as `vouched`: at least one valid voucher and no
     * explicit verification of mine (an identity I verified outranks the vouched tier).
     */
    suspend fun isVouched(fingerprint: Fingerprint): Boolean

    /**
     * The fingerprints of my verified peers that currently vouch for [fingerprint]. A stored vouch
     * counts only while its voucher remains verified-by-me and its attestation is inside its
     * validity window.
     */
    suspend fun validVouchers(fingerprint: Fingerprint): List<Fingerprint>
}
