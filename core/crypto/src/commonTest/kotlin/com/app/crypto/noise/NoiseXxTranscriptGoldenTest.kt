package com.app.crypto.noise

import com.app.crypto.noise.southernstorm.protocol.HandshakeState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wire-parity anchor for the Noise engine (iOS compatibility is sacred).
 *
 * Drives a full Noise_XX_25519_ChaChaPoly_SHA256 handshake between two parties with FIXED static
 * and ephemeral keys, producing a deterministic transcript. The expected bytes were captured from
 * the proven JVM-backed engine; they MUST stay byte-identical as the engine is de-JVM'd and moved
 * to commonMain. Any divergence means the on-wire handshake changed.
 *
 * (Lives in androidHostTest while the engine is in androidMain; moves to commonTest once the engine
 * is in commonMain so it also guards the iOS target.)
 */
class NoiseXxTranscriptGoldenTest {

    private val proto = "Noise_XX_25519_ChaChaPoly_SHA256"
    private val initStatic = ByteArray(32) { 1 }
    private val initEph = ByteArray(32) { 2 }
    private val respStatic = ByteArray(32) { 3 }
    private val respEph = ByteArray(32) { 4 }

    private val expectedM1 =
        "ce8d3ad1ccb633ec7b70c17814a5c76ecd029685050d344745ba05870e587d59"
    private val expectedM2 =
        "ac01b2209e86354fb853237b5de0f4fab13c7fcbf433a61c019369617fecf10b" +
            "b719b14d19eaf5ca91c89748c5ce8668e6864f05ee2367682b3e0c2fd086f0b4" +
            "f64162733f3fe548251c518fe8c03cce5fc0bcf3f8dea9b003f930ab273300e0"
    private val expectedM3 =
        "539a5cf3ae8a0a9134b32bfa775a1522db3558a1351ed4101989b8b88aa6a6f0" +
            "20da6d184283343a1d38fad23b3af58bc614f9b447f9aeb2a583c37b06bc4265"
    private val expectedHash =
        "21acd1864c5e1ad3a31a5878ece019767878774200e5cadca6199731b2cf009b"

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun xx_transcript_is_byte_identical() {
        val initiator = HandshakeState(proto, HandshakeState.INITIATOR)
        initiator.localKeyPair!!.setPrivateKey(initStatic, 0)
        initiator.fixedEphemeralKey!!.setPrivateKey(initEph, 0)

        val responder = HandshakeState(proto, HandshakeState.RESPONDER)
        responder.localKeyPair!!.setPrivateKey(respStatic, 0)
        responder.fixedEphemeralKey!!.setPrivateKey(respEph, 0)

        initiator.start()
        responder.start()

        val msgs = mutableListOf<ByteArray>()
        while (initiator.action != HandshakeState.SPLIT || responder.action != HandshakeState.SPLIT) {
            val writer = if (initiator.action == HandshakeState.WRITE_MESSAGE) initiator else responder
            val reader = if (writer === initiator) responder else initiator
            val buf = ByteArray(2048)
            val n = writer.writeMessage(buf, 0, null, 0, 0)
            val msg = buf.copyOf(n)
            msgs += msg
            reader.readMessage(msg, 0, msg.size, ByteArray(2048), 0)
        }

        assertEquals(3, msgs.size)
        assertEquals(expectedM1, msgs[0].hex())
        assertEquals(expectedM2, msgs[1].hex())
        assertEquals(expectedM3, msgs[2].hex())
        assertEquals(expectedHash, initiator.handshakeHash!!.hex())
        // Both parties must derive the same handshake hash (channel-binding agreement).
        assertEquals(initiator.handshakeHash!!.hex(), responder.handshakeHash!!.hex())
    }
}
