package com.yet.bitmessage.android.verification

import com.app.common.AppDispatchers
import com.app.common.encoding.hexEncodedString
import com.app.crypto.noise.NoiseSession
import com.app.domain.model.Fingerprint
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.PeerVerificationRepository
import com.app.domain.repository.VerifyScanResult
import com.app.transport.VerificationService
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.verification.VerifyEventListener
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase C owner of the Noise QR-verification orchestration, re-homed from the legacy
 * `VerificationHandler` (which only ran while the legacy `ChatViewModel` was the BMS delegate).
 * Attaches itself as [BluetoothMeshService.verifyEventListener] so inbound challenges/responses are
 * handled in the Decompose app. All crypto is delegated to [VerificationService] — none is
 * reimplemented here. On a completed round-trip the peer's fingerprint is marked verified via
 * [IdentityRepository.setVerified], which the contacts / chat-header indicators already observe.
 */
@SingleIn(AppScope::class)
@Inject
class VerificationCoordinator(
    private val meshService: BluetoothMeshService,
    private val verificationService: VerificationService,
    private val identityRepository: IdentityRepository,
    dispatchers: AppDispatchers,
) : PeerVerificationRepository, VerifyEventListener {

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())

    private data class Pending(
        val noiseKeyHex: String,
        val signKeyHex: String,
        val nonceA: ByteArray,
        val sent: Boolean,
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val lastInboundNonceByPeer = ConcurrentHashMap<String, ByteArray>()

    init {
        meshService.verifyEventListener = this
    }

    override suspend fun verifyScannedQr(payload: String): VerifyScanResult {
        val qr = verificationService.verifyScannedQR(payload) ?: return VerifyScanResult.Invalid
        val targetNoise = qr.noiseKeyHex.lowercase()
        val peerID = meshService.getPeerNicknames().keys.firstOrNull { pid ->
            val noiseHex = runCatching {
                meshService.getPeerInfo(pid)?.noisePublicKey?.hexEncodedString()?.lowercase()
            }.getOrNull()
            noiseHex == targetNoise
        } ?: return VerifyScanResult.PeerNotFound

        if (!pending.containsKey(peerID)) {
            val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
            val entry = Pending(qr.noiseKeyHex, qr.signKeyHex, nonce, sent = false)
            pending[peerID] = entry
            if (meshService.getSessionState(peerID) is NoiseSession.NoiseSessionState.Established) {
                meshService.sendVerifyChallenge(peerID, qr.noiseKeyHex, nonce)
                pending[peerID] = entry.copy(sent = true)
            } else {
                // The challenge is sent once the handshake establishes (see sendPendingIfNeeded).
                meshService.initiateNoiseHandshake(peerID)
            }
        }
        return VerifyScanResult.Started(qr.nickname)
    }

    /** Re-send a queued challenge once the Noise session is up; call from the session-established hook. */
    fun sendPendingIfNeeded(peerID: String) {
        val entry = pending[peerID] ?: return
        if (entry.sent) return
        meshService.sendVerifyChallenge(peerID, entry.noiseKeyHex, entry.nonceA)
        pending[peerID] = entry.copy(sent = true)
    }

    override fun onVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        scope.launch {
            val parsed = verificationService.parseVerifyChallenge(payload) ?: return@launch
            val (challengedNoiseHex, nonceA) = parsed
            val myNoiseHex = meshService.getStaticNoisePublicKey()?.hexEncodedString()?.lowercase() ?: return@launch
            // Only answer challenges that target our own Noise identity.
            if (challengedNoiseHex.lowercase() != myNoiseHex) return@launch

            val last = lastInboundNonceByPeer[peerID]
            if (last != null && last.contentEquals(nonceA)) return@launch
            lastInboundNonceByPeer[peerID] = nonceA

            meshService.sendVerifyResponse(peerID, challengedNoiseHex, nonceA)
        }
    }

    override fun onVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        scope.launch {
            val resp = verificationService.parseVerifyResponse(payload) ?: return@launch
            val entry = pending[peerID] ?: return@launch
            if (!resp.noiseKeyHex.equals(entry.noiseKeyHex, ignoreCase = true)) return@launch
            if (!resp.nonceA.contentEquals(entry.nonceA)) return@launch

            val ok = verificationService.verifyResponseSignature(
                noiseKeyHex = resp.noiseKeyHex,
                nonceA = resp.nonceA,
                signature = resp.signature,
                signerPublicKeyHex = entry.signKeyHex,
            )
            if (!ok) return@launch

            pending.remove(peerID)
            val fp = meshService.getPeerFingerprint(peerID) ?: return@launch
            identityRepository.setVerified(Fingerprint(fp), true)
        }
    }

    private companion object {
        const val NONCE_LEN = 16
    }
}
