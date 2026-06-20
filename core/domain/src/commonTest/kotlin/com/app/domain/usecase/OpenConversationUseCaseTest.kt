package com.app.domain.usecase

import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.PeerId
import com.app.domain.repository.NoiseSessionPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenConversationUseCaseTest {

    private class FakeSession(
        private val established: Boolean = false,
        private val myId: String = "5555555555555555",
    ) : NoiseSessionPort {
        var handshakes = 0
        var announces = 0
        override fun hasSession(peerId: PeerId): Boolean = established
        override fun myPeerId(): String = myId
        override fun initiateHandshake(peerId: PeerId) { handshakes++ }
        override fun announceTo(peerId: PeerId) { announces++ }
    }

    private val lowPeer = PeerId("1111111111111111")   // < myId
    private val highPeer = PeerId("9999999999999999")  // > myId

    @Test fun no_handshake_when_session_already_established() {
        val session = FakeSession(established = true)
        OpenConversationUseCase(session)(ConversationId.Private(lowPeer))
        assertEquals(0, session.handshakes)
        assertEquals(0, session.announces)
    }

    @Test fun smaller_local_id_only_initiates() {
        val session = FakeSession(myId = "5555555555555555")
        OpenConversationUseCase(session)(ConversationId.Private(highPeer))
        assertEquals(1, session.handshakes)
        assertEquals(0, session.announces)
    }

    @Test fun larger_local_id_announces_then_initiates() {
        val session = FakeSession(myId = "5555555555555555")
        OpenConversationUseCase(session)(ConversationId.Private(lowPeer))
        assertEquals(1, session.announces)
        assertEquals(1, session.handshakes)
    }

    @Test fun stable_noise_key_peer_is_skipped() {
        val session = FakeSession()
        OpenConversationUseCase(session)(ConversationId.Private(PeerId("a".repeat(64))))
        assertEquals(0, session.handshakes)
        assertEquals(0, session.announces)
    }

    @Test fun non_private_conversations_are_ignored() {
        val session = FakeSession()
        val useCase = OpenConversationUseCase(session)
        useCase(ConversationId.PublicMesh)
        useCase(ConversationId.Channel("#dev"))
        useCase(ConversationId.Geohash(GeohashChannel(GeohashLevel.CITY, "u4pru")))
        assertEquals(0, session.handshakes)
        assertFalse(session.announces > 0)
    }

    @Test fun equal_id_announces_then_initiates() {
        val session = FakeSession(myId = lowPeer.raw)
        OpenConversationUseCase(session)(ConversationId.Private(lowPeer))
        assertTrue(session.announces == 1 && session.handshakes == 1)
    }
}
