package com.app.transport.vouch

/**
 * Inbound vouch batches (Noise payload 0x12), surfaced as a narrow SPI so the platform-free vouch
 * coordinator can own the trust policy without implementing the full
 * [BluetoothMeshDelegate][com.app.transport.mesh.BluetoothMeshDelegate]. Mirrors
 * [VerifyEventListener][com.app.transport.verification.VerifyEventListener].
 *
 * The payload is the raw batch body; the voucher is the session peer, so the listener must resolve
 * [peerID] to a fingerprint and verify every attestation against that peer's announce-bound signing
 * key before storing anything.
 */
interface VouchEventListener {

    fun onVouchAttestations(peerID: String, payload: ByteArray, timestampMs: Long)

    /** A Noise session came up with [peerID] (fingerprint known): a vouch batch may be due. */
    fun onPeerAuthenticated(peerID: String, fingerprint: String)
}
