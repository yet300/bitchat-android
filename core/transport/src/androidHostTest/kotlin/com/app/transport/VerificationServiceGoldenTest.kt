package com.app.transport

import com.app.common.encoding.hexEncodedString
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.secure.SecureKeyValueStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Wire/QR golden for [VerificationService.VerificationQR] and the verify challenge/response
 * TLV framing. These bytes are signed and cross-scanned with iOS, so the canonical buffers and
 * the `bitchat://verify` URL string must stay byte-identical across the de-JVM to commonMain.
 *
 * Runs under Robolectric so the current androidMain implementation (android.net.Uri) resolves;
 * the ported commonMain implementation passes the same assertions without Robolectric help.
 */
@RunWith(RobolectricTestRunner::class)
class VerificationServiceGoldenTest {

    private val noiseKeyHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    private val signKeyHex = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"
    private val nickname = "Al ce!~π" // space + reserved + unicode (pi) to exercise URL encoding
    private val ts = 1700000000L
    private val nonceB64 = "QUJDREVGR0hJSktMTU5P"
    private val sigHex = "0102030405060708090a0b0c0d0e0f10" +
        "1112131415161718191a1b1c1d1e1f20"
    private val npub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsh0d2p"

    private fun qr(np: String?) = VerificationService.VerificationQR(
        v = 1,
        noiseKeyHex = noiseKeyHex,
        signKeyHex = signKeyHex,
        npub = np,
        nickname = nickname,
        ts = ts,
        nonceB64 = nonceB64,
        sigHex = sigHex,
    )

    private fun service(): VerificationService =
        VerificationService(EncryptionService(NoopStore, PeerFingerprintManager()))

    // ---- Locked goldens (captured from the androidMain implementation) ----

    @Test
    fun canonicalBytes_noNpub_golden() {
        assertEquals(GOLDEN_CANONICAL_NO_NPUB, qr(null).canonicalBytes().hexEncodedString())
    }

    @Test
    fun canonicalBytes_npub_golden() {
        // npub IS part of the canonical (signed) bytes (appendField(npub ?: "")), so it differs.
        assertEquals(GOLDEN_CANONICAL_NPUB, qr(npub).canonicalBytes().hexEncodedString())
    }

    @Test
    fun toUrlString_noNpub_golden() {
        assertEquals(GOLDEN_URL_NO_NPUB, qr(null).toUrlString())
    }

    @Test
    fun toUrlString_npub_golden() {
        assertEquals(GOLDEN_URL_NPUB, qr(npub).toUrlString())
    }

    @Test
    fun buildVerifyChallenge_golden() {
        val nonceA = ByteArray(16) { (it + 1).toByte() }
        assertEquals(GOLDEN_CHALLENGE, service().buildVerifyChallenge(noiseKeyHex, nonceA).hexEncodedString())
    }

    // ---- Round-trips (port-stable, no fixed golden needed) ----

    @Test
    fun fromUrlString_roundTrips() {
        val q = qr(npub)
        val parsed = VerificationService.VerificationQR.fromUrlString(q.toUrlString())!!
        assertEquals(q.v, parsed.v)
        assertEquals(q.noiseKeyHex, parsed.noiseKeyHex)
        assertEquals(q.signKeyHex, parsed.signKeyHex)
        assertEquals(q.npub, parsed.npub)
        assertEquals(q.nickname, parsed.nickname)
        assertEquals(q.ts, parsed.ts)
        assertEquals(q.nonceB64, parsed.nonceB64)
        assertEquals(q.sigHex, parsed.sigHex)
    }

    @Test
    fun challenge_roundTrips() {
        val svc = service()
        val nonceA = ByteArray(16) { (it + 1).toByte() }
        val ch = svc.buildVerifyChallenge(noiseKeyHex, nonceA)
        val (noise, nonce) = svc.parseVerifyChallenge(ch)!!
        assertEquals(noiseKeyHex, noise)
        assertArrayEquals(nonceA, nonce)
    }

    @Test
    fun response_roundTrips_and_verifies() {
        val svc = service()
        val enc = EncryptionService(NoopStore, PeerFingerprintManager())
        val signer = VerificationService(enc)
        val signerKeyHex = enc.getSigningPublicKey()!!.hexEncodedString()
        val nonceA = ByteArray(16) { (it * 7 + 3).toByte() }

        val resp = signer.buildVerifyResponse(noiseKeyHex, nonceA)!!
        val parsed = svc.parseVerifyResponse(resp)!!
        assertEquals(noiseKeyHex, parsed.noiseKeyHex)
        assertArrayEquals(nonceA, parsed.nonceA)
        assertTrue(
            svc.verifyResponseSignature(parsed.noiseKeyHex, parsed.nonceA, parsed.signature, signerKeyHex)
        )
    }

    private object NoopStore : SecureKeyValueStore {
        override fun getString(key: String): String? = null
        override fun putString(key: String, value: String) {}
        override fun getStringSet(key: String): Set<String>? = null
        override fun putStringSet(key: String, values: Set<String>) {}
        override fun contains(key: String): Boolean = false
        override fun remove(vararg keys: String) {}
        override suspend fun clear() {}
    }

    private companion object {
        // Captured verbatim from the androidMain implementation prior to the commonMain port.
        const val GOLDEN_CANONICAL_NO_NPUB = "11626974636861742d7665726966792d76310131403030313132323333343435353636373738383939616162626363646465656666303031313232333334343535363637373838393961616262636364646565666640666665656464636362626161393938383737363635353434333332323131303066666565646463636262616139393838373736363535343433333232313130300009416c206365217ecf800a313730303030303030301451554a44524556475230684a536b744d54553550"
        const val GOLDEN_CANONICAL_NPUB = "11626974636861742d7665726966792d76310131403030313132323333343435353636373738383939616162626363646465656666303031313232333334343535363637373838393961616262636364646565666640666665656464636362626161393938383737363635353434333332323131303066666565646463636262616139393838373736363535343433333232313130303b6e7075623171717171717171717171717171717171717171717171717171717171717171717171717171717171717171717171717173683064327009416c206365217ecf800a313730303030303030301451554a44524556475230684a536b744d54553550"
        const val GOLDEN_URL_NO_NPUB = "bitchat://verify?v=1&noise=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff&sign=ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100&nick=Al%20ce!~%CF%80&ts=1700000000&nonce=QUJDREVGR0hJSktMTU5P&sig=0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
        const val GOLDEN_URL_NPUB = GOLDEN_URL_NO_NPUB + "&npub=npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsh0d2p"
        const val GOLDEN_CHALLENGE = "01403030313132323333343435353636373738383939616162626363646465656666303031313232333334343535363637373838393961616262636364646565666602100102030405060708090a0b0c0d0e0f10"
    }
}
