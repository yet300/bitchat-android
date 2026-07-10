package com.app.crypto.identity

import com.app.crypto.secure.InMemorySecureKeyValueStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Vouch-supporting state added to the identity store: verifiedAt ordering, announce-bound signing
 *  key cache, and the persisted vouch-batch rate-limit stamp. */
@RunWith(RobolectricTestRunner::class)
class SecureIdentityStateManagerVouchTest {

    private lateinit var store: InMemorySecureKeyValueStore
    private lateinit var manager: SecureIdentityStateManager

    private val fpA = "a".repeat(64)
    private val fpB = "b".repeat(64)
    private val fpC = "c".repeat(64)

    @Before
    fun setup() {
        store = InMemorySecureKeyValueStore()
        manager = SecureIdentityStateManager(store)
    }

    @Test
    fun `mostRecentlyVerified orders by verification time, newest first`() {
        manager.setVerifiedFingerprint(fpA, true, nowMs = 1_000)
        manager.setVerifiedFingerprint(fpB, true, nowMs = 3_000)
        manager.setVerifiedFingerprint(fpC, true, nowMs = 2_000)

        assertEquals(listOf(fpB, fpC, fpA), manager.mostRecentlyVerifiedFingerprints(limit = 10))
    }

    @Test
    fun `mostRecentlyVerified honours the exclusion and the limit`() {
        manager.setVerifiedFingerprint(fpA, true, nowMs = 1_000)
        manager.setVerifiedFingerprint(fpB, true, nowMs = 3_000)
        manager.setVerifiedFingerprint(fpC, true, nowMs = 2_000)

        assertEquals(listOf(fpC, fpA), manager.mostRecentlyVerifiedFingerprints(limit = 10, excluding = fpB))
        assertEquals(listOf(fpB), manager.mostRecentlyVerifiedFingerprints(limit = 1))
        assertTrue(manager.mostRecentlyVerifiedFingerprints(limit = 0).isEmpty())
    }

    @Test
    fun `unverifying clears the verification timestamp`() {
        manager.setVerifiedFingerprint(fpA, true, nowMs = 5_000)
        manager.setVerifiedFingerprint(fpA, false, nowMs = 6_000)
        assertTrue(manager.mostRecentlyVerifiedFingerprints(limit = 10).isEmpty())
    }

    @Test
    fun `signing key cache round-trips and rejects wrong sizes`() {
        val key = ByteArray(32) { (it + 1).toByte() }
        manager.cacheSigningPublicKey(fpA, key)
        assertArrayEquals(key, manager.getSigningPublicKey(fpA))

        // Wrong size is ignored, not stored.
        manager.cacheSigningPublicKey(fpB, ByteArray(31))
        assertNull(manager.getSigningPublicKey(fpB))
        assertNull(manager.getSigningPublicKey(fpC))
    }

    @Test
    fun `latest cached signing key replaces the previous one`() {
        manager.cacheSigningPublicKey(fpA, ByteArray(32) { 1 })
        manager.cacheSigningPublicKey(fpA, ByteArray(32) { 2 })
        assertArrayEquals(ByteArray(32) { 2 }, manager.getSigningPublicKey(fpA))
    }

    @Test
    fun `vouch batch stamp persists and can be read back`() {
        assertNull(manager.lastVouchBatchSentAt(fpA))
        manager.markVouchBatchSent(fpA, atMs = 42_000)
        assertEquals(42_000L, manager.lastVouchBatchSentAt(fpA))

        // A second manager over the same store sees the persisted stamp.
        assertEquals(42_000L, SecureIdentityStateManager(store).lastVouchBatchSentAt(fpA))
    }

    @Test
    fun `clearIdentityKeysImmediate wipes the vouch-supporting state`() {
        manager.setVerifiedFingerprint(fpA, true, nowMs = 1_000)
        manager.cacheSigningPublicKey(fpA, ByteArray(32) { 7 })
        manager.markVouchBatchSent(fpA, atMs = 2_000)

        manager.clearIdentityKeysImmediate()

        assertTrue(manager.mostRecentlyVerifiedFingerprints(limit = 10).isEmpty())
        assertNull(manager.getSigningPublicKey(fpA))
        assertNull(manager.lastVouchBatchSentAt(fpA))
    }
}
