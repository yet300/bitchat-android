package com.app.crypto

import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.secure.InMemorySecureKeyValueStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One-way Noise X courier seal round-trip: a sender seals a payload to a recipient's static key,
 * and only the recipient can open it, recovering both the payload and the sender's authenticated
 * static key (the `ss` DH binds sender identity into the ciphertext).
 */
@RunWith(RobolectricTestRunner::class)
class CourierSealTest {

    private fun service() = EncryptionService(InMemorySecureKeyValueStore(), PeerFingerprintManager())

    @Test
    fun `seal then open recovers payload and authenticates sender`() {
        val sender = service()
        val recipient = service()
        val recipientStatic = recipient.getStaticPublicKey()!!
        val senderStatic = sender.getStaticPublicKey()!!
        val payload = "courier: the meeting is at dawn".encodeToByteArray()

        val ciphertext = sender.sealCourierPayload(payload, recipientStatic)
        assertNotNull("seal should succeed", ciphertext)

        val opened = recipient.openCourierPayload(ciphertext!!)
        assertNotNull("recipient can open", opened)
        assertArrayEquals("payload recovered", payload, opened!!.first)
        assertArrayEquals("sender static key authenticated", senderStatic, opened.second)
    }

    @Test
    fun `a third party cannot open the envelope`() {
        val sender = service()
        val recipient = service()
        val eavesdropper = service()
        val payload = "secret".encodeToByteArray()

        val ciphertext = sender.sealCourierPayload(payload, recipient.getStaticPublicKey()!!)!!
        assertNull("wrong static key must not open", eavesdropper.openCourierPayload(ciphertext))
    }

    @Test
    fun `each seal of the same payload differs (fresh ephemeral)`() {
        val sender = service()
        val recipientStatic = service().getStaticPublicKey()!!
        val payload = "same".encodeToByteArray()

        val a = sender.sealCourierPayload(payload, recipientStatic)!!
        val b = sender.sealCourierPayload(payload, recipientStatic)!!
        assertTrue("ciphertexts must differ across seals", !a.contentEquals(b))
    }

    @Test
    fun `tampered ciphertext fails to open`() {
        val sender = service()
        val recipient = service()
        val ciphertext = sender.sealCourierPayload("hi".encodeToByteArray(), recipient.getStaticPublicKey()!!)!!
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1] + 1).toByte()
        assertNull("AEAD tag rejects tampering", recipient.openCourierPayload(ciphertext))
    }
}
