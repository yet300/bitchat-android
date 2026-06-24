package com.app.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.secure.InMemorySecureKeyValueStore
import com.app.crypto.secure.SecureStores
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Arrays

@RunWith(RobolectricTestRunner::class)
class EncryptionServiceTest {

    private lateinit var context: Context
    private lateinit var encryptionService: EncryptionService

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric has no Android Keystore, so the KSafe-backed secure store fails closed. Install
        // a single in-memory store shared across every component the service constructs.
        val store = InMemorySecureKeyValueStore()
        SecureStores.factory = { store }
        encryptionService = EncryptionService(context, PeerFingerprintManager())
    }

    @After
    fun tearDown() {
        SecureStores.resetFactory()
    }

    @Test
    fun `test clearPersistentIdentity changes keys`() {
        // 1. Get initial keys
        val initialStaticKey = encryptionService.getStaticPublicKey()
        val initialSigningKey = encryptionService.getSigningPublicKey()
        val initialFingerprint = encryptionService.getIdentityFingerprint()

        assertNotNull("Initial static key should not be null", initialStaticKey)
        assertNotNull("Initial signing key should not be null", initialSigningKey)

        // 2. Call clearPersistentIdentity (Panic Mode)
        encryptionService.clearPersistentIdentity()

        // 3. Get keys again. 
        val afterStaticKey = encryptionService.getStaticPublicKey()
        val afterSigningKey = encryptionService.getSigningPublicKey()
        val afterFingerprint = encryptionService.getIdentityFingerprint()

        // 4. Verify keys are different (Panic Mode should clear/rotate in-memory keys)
        // Note: We use string comparison for byte arrays to be safe in assertion messages
        assertNotEquals("Static key should change after panic", 
            Arrays.toString(initialStaticKey), Arrays.toString(afterStaticKey))
        
        assertNotEquals("Signing key should change after panic", 
            Arrays.toString(initialSigningKey), Arrays.toString(afterSigningKey))

        assertNotEquals("Fingerprint should change after panic", 
            initialFingerprint, afterFingerprint)
    }
}
