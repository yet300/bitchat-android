package com.app.transport.verification

/**
 * Inbound mesh verification events (Noise challenge/response packets), surfaced as a narrow SPI so
 * the Phase C app can own the QR-verification orchestration without implementing the full
 * [BluetoothMeshDelegate][com.app.transport.mesh.BluetoothMeshDelegate]. Mirrors the other app-side
 * wiring SPIs (ServiceNotifier, IncomingMessageSink). The implementation lives in the app/graph and
 * reuses [VerificationService][com.app.transport.VerificationService] for all crypto.
 */
interface VerifyEventListener {

    fun onVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long)

    fun onVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long)
}
