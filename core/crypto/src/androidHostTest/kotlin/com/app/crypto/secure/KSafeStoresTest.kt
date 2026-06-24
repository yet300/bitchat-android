package com.app.crypto.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyStore

/**
 * P1 smoke test: the two [KSafeStores] instances round-trip put/get. Proves the KSafe instances
 * construct and persist under the Android runtime.
 *
 * Plain mode runs everywhere. The encrypted path needs a real Android Keystore (AES key in the
 * TEE); KSafe fails closed when it is absent rather than degrading to plaintext, so under Robolectric
 * — which ships no `AndroidKeyStore` provider — the encrypted case is skipped (assumeTrue) and is
 * instead proven on-device (protectionInfo + on-disk ciphertext inspection). KSafe's own library
 * tests its encryption only via androidDeviceTest for the same reason.
 */
@RunWith(RobolectricTestRunner::class)
class KSafeStoresTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `plain prefs round-trips a value`() = runTest {
        val prefs = KSafeStores.prefs(context)
        prefs.put("smoke_plain", "hello", mode = KSafeWriteMode.Plain)
        assertEquals("hello", prefs.get("smoke_plain", ""))
    }

    @Test
    fun `encrypted vault round-trips a value`() = runTest {
        assumeTrue("Android Keystore required for KSafe encrypted mode", androidKeystoreAvailable())
        val vault = KSafeStores.vault(context)
        vault.put("smoke_secret", "s3cr3t")
        assertEquals("s3cr3t", vault.get("smoke_secret", ""))
    }

    private fun androidKeystoreAvailable(): Boolean = runCatching {
        KeyStore.getInstance("AndroidKeyStore").load(null)
    }.isSuccess
}
