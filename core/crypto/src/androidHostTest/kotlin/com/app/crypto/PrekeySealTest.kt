package com.app.crypto

import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.secure.InMemorySecureKeyValueStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Forward-secret prekey (envelope v2) seal round-trip: a sender seals a payload to one of the
 * recipient's gossiped one-time prekeys, only the recipient can open it (recovering the payload and
 * the sender's authenticated static key), the prekey is one-time (a second open of a *fresh*
 * ciphertext to the same ID is refused once consumed and grace-expired), and an unknown prekey ID
 * is rejected.
 */
@RunWith(RobolectricTestRunner::class)
class PrekeySealTest {

    private fun service() = EncryptionService(InMemorySecureKeyValueStore(), PeerFingerprintManager())

    @Test
    fun `seal to a prekey then open recovers payload and authenticates sender`() {
        val sender = service()
        val recipient = service()
        val senderStatic = sender.getStaticPublicKey()!!
        val (prekeys, _) = recipient.currentBundlePrekeys()
        val prekey = prekeys.first()
        val payload = "prekey: rendezvous at the old pier".encodeToByteArray()

        val ciphertext = sender.sealPrekeyPayload(payload, prekey.id, prekey.publicKey)
        assertNotNull("seal should succeed", ciphertext)

        val opened = recipient.openPrekeyPayload(ciphertext!!, prekey.id)
        assertNotNull("recipient can open", opened)
        assertArrayEquals("payload recovered", payload, opened!!.payload)
        assertArrayEquals("sender static key authenticated", senderStatic, opened.senderStaticKey)
        assertTrue("first open retires the prekey", opened.consumedPrekey)
    }

    @Test
    fun `a third party cannot open a prekey envelope`() {
        val sender = service()
        val recipient = service()
        val eavesdropper = service()
        val prekey = recipient.currentBundlePrekeys().first.first()

        val ciphertext = sender.sealPrekeyPayload("secret".encodeToByteArray(), prekey.id, prekey.publicKey)!!
        // The eavesdropper does not hold that prekey ID at all.
        assertNull("unknown prekey ID is rejected", eavesdropper.openPrekeyPayload(ciphertext, prekey.id))
    }

    @Test
    fun `opening an unknown prekey id is rejected`() {
        val sender = service()
        val recipient = service()
        val prekey = recipient.currentBundlePrekeys().first.first()
        val ciphertext = sender.sealPrekeyPayload("hi".encodeToByteArray(), prekey.id, prekey.publicKey)!!
        // A prekey ID the recipient never minted.
        assertNull(recipient.openPrekeyPayload(ciphertext, prekeyID = 999u))
    }

    @Test
    fun `prologue is bound to the prekey id`() {
        val sender = service()
        val recipient = service()
        val prekeys = recipient.currentBundlePrekeys().first
        val a = prekeys[0]
        val b = prekeys[1]
        // Seal to prekey a, then try to open under prekey b's ID: the prologue mismatch fails the AEAD.
        val ciphertext = sender.sealPrekeyPayload("bound".encodeToByteArray(), a.id, a.publicKey)!!
        assertNull("ciphertext cannot be opened against a different prekey", recipient.openPrekeyPayload(ciphertext, b.id))
    }

    @Test
    fun `tampered prekey ciphertext fails to open`() {
        val sender = service()
        val recipient = service()
        val prekey = recipient.currentBundlePrekeys().first.first()
        val ciphertext = sender.sealPrekeyPayload("hi".encodeToByteArray(), prekey.id, prekey.publicKey)!!
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1] + 1).toByte()
        assertNull("AEAD tag rejects tampering", recipient.openPrekeyPayload(ciphertext, prekey.id))
    }

    @Test
    fun `redelivery within grace re-opens but does not re-retire`() {
        val sender = service()
        val recipient = service()
        val prekey = recipient.currentBundlePrekeys().first.first()
        val payload = "same envelope, two couriers".encodeToByteArray()
        val ciphertext = sender.sealPrekeyPayload(payload, prekey.id, prekey.publicKey)!!

        val first = recipient.openPrekeyPayload(ciphertext, prekey.id)!!
        assertTrue("first open retires the prekey", first.consumedPrekey)

        // A duplicate courier redelivery of the SAME ciphertext still opens within the 48h grace,
        // but reports no new retirement (so the caller doesn't spuriously re-gossip).
        val second = recipient.openPrekeyPayload(ciphertext, prekey.id)!!
        assertArrayEquals(payload, second.payload)
        assertFalse("redelivery within grace is not a fresh retirement", second.consumedPrekey)
    }

    @Test
    fun `panic wipe drops prekey privates so sealed mail cannot open`() {
        val sender = service()
        val recipient = service()
        val prekey = recipient.currentBundlePrekeys().first.first()
        val ciphertext = sender.sealPrekeyPayload("gone".encodeToByteArray(), prekey.id, prekey.publicKey)!!

        recipient.clearPersistentIdentity()
        assertNull("wiped prekey privates cannot open old mail", recipient.openPrekeyPayload(ciphertext, prekey.id))
        // The rotated identity mints a brand-new batch.
        assertEquals(8, recipient.currentBundlePrekeys().first.size)
    }
}
