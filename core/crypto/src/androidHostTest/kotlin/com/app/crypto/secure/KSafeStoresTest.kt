package com.app.crypto.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.anifantakis.lib.ksafe.KSafe
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
 * P1 smoke test: a plain and an encrypted [KSafe] instance round-trip put/get. Proves KSafe
 * constructs and persists under the Android runtime. (KSafe construction itself now lives in the
 * :shared DI graph; this test exercises the library contract the crypto layer relies on.)
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
        val prefs = KSafe(context.applicationContext, fileName = "bitchat_prefs")
        prefs.put("smoke_plain", "hello", mode = KSafeWriteMode.Plain)
        assertEquals("hello", prefs.get("smoke_plain", ""))
    }

    @Test
    fun `encrypted vault round-trips a value`() = runTest {
        assumeTrue("Android Keystore required for KSafe encrypted mode", androidKeystoreAvailable())
        val vault = KSafe(context.applicationContext, fileName = "bitchat_vault")
        vault.put("smoke_secret", "s3cr3t")
        assertEquals("s3cr3t", vault.get("smoke_secret", ""))
    }

    private fun androidKeystoreAvailable(): Boolean = runCatching {
        KeyStore.getInstance("AndroidKeyStore").load(null)
    }.isSuccess
}
