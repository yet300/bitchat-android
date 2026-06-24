package com.app.crypto.secure

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeProtectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves KSafe encrypts secrets at rest. Runs on a device/emulator because KSafe's encrypted mode
 * needs a real Android Keystore (it fails closed under Robolectric), so this is deliberately NOT part
 * of the host unit gate — same situation as [com.app.database.SqlCipherEncryptionTest] (FIX_BACKLOG
 * §F: the androidDeviceTest source set is inert until on-device testing is wired via
 * `withDeviceTestBuilder`).
 *
 * Three assertions, mirroring the on-device verification step of the migration:
 *  1. an encrypted value round-trips,
 *  2. the cleartext marker never appears anywhere in the on-disk store (true ciphertext at rest),
 *  3. protectionInfo / per-key level report a Keystore-backed tier (not SOFTWARE).
 */
@RunWith(AndroidJUnit4::class)
class KSafeEncryptionProofTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val marker = "PLAINTEXT_SECRET_MARKER_5f3a9c"

    @Test
    fun encryptedSecret_roundTrips_andIsCiphertextOnDisk() {
        val ksafe = KSafe(context.applicationContext, fileName = "ksafe_proof")
        ksafe.putDirect("proof_key", marker)            // encrypted by default

        // 1. Round-trips through the hot cache / decryption.
        assertEquals(marker, ksafe.getDirect("proof_key", ""))

        // 2. The cleartext marker is absent from every on-disk byte under the app's storage dirs.
        val roots = listOfNotNull(context.filesDir, context.noBackupFilesDir, context.cacheDir)
        val markerBytes = marker.encodeToByteArray()
        val leaked = roots.flatMap { it.walkTopDown().filter(File::isFile).toList() }
            .any { it.readBytes().containsSubsequence(markerBytes) }
        assertFalse("Plaintext secret marker found on disk — value was not encrypted", leaked)

        // 3. Key custody is Keystore-backed, not the software fallback.
        val info = ksafe.protectionInfo
        assertNotNull(info)
        assertTrue(
            "Expected Keystore-backed protection, got ${info.effectiveLevel} (${info.custody})",
            info.effectiveLevel >= KSafeProtectionLevel.HARDWARE_BACKED,
        )
        assertTrue(
            "Per-key level should be Keystore-backed: ${ksafe.getKeyInfo("proof_key")?.level}",
            (ksafe.getKeyInfo("proof_key")?.level ?: KSafeProtectionLevel.SOFTWARE)
                >= KSafeProtectionLevel.HARDWARE_BACKED,
        )
    }

    private fun ByteArray.containsSubsequence(sub: ByteArray): Boolean {
        if (sub.isEmpty() || size < sub.size) return false
        outer@ for (i in 0..size - sub.size) {
            for (j in sub.indices) if (this[i + j] != sub[j]) continue@outer
            return true
        }
        return false
    }
}
