@file:OptIn(ExperimentalTime::class)

package com.app.domain.usecase

import com.app.domain.FakeMessageRepository
import com.app.domain.FakeMessageTransport
import com.app.domain.model.ConversationId
import com.app.domain.model.DeliveryStatus
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.PeerId
import com.app.domain.model.SenderRef
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class SendMessageUseCaseTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    }
    private val sender = SenderRef(PeerId("1111111111111111"), "me")

    private fun useCase(transport: FakeMessageTransport, repo: FakeMessageRepository) =
        SendMessageUseCase(transport, repo, clock = fixedClock, newId = { "MSG-1" })

    @Test fun `blank content does nothing`() = runTest {
        val transport = FakeMessageTransport(); val repo = FakeMessageRepository()
        useCase(transport, repo).invoke(ConversationId.PublicMesh, "   ", sender)
        assertTrue(repo.appended.isEmpty())
        assertTrue(transport.publics.isEmpty())
    }

    @Test fun `public message echoes and sends without channel`() = runTest {
        val transport = FakeMessageTransport(); val repo = FakeMessageRepository()
        useCase(transport, repo).invoke(ConversationId.PublicMesh, "hi", sender, mentions = listOf("bob"))

        assertEquals(1, repo.appended.size)
        val echo = repo.appended.single().second
        assertEquals("hi", echo.content)
        assertTrue(echo.isMine)
        assertEquals(listOf("bob"), echo.mentions)
        assertEquals(1, transport.publics.size)
        assertEquals(null, transport.publics.single().channel)
    }

    @Test fun `channel message routes with tag`() = runTest {
        val transport = FakeMessageTransport(); val repo = FakeMessageRepository()
        val target = ConversationId.Channel("#dev")
        useCase(transport, repo).invoke(target, "hey", sender)

        assertEquals(target, repo.appended.single().first)
        assertEquals("#dev", transport.publics.single().channel)
    }

    @Test fun `private message echoes Sending and sends with id`() = runTest {
        val transport = FakeMessageTransport(); val repo = FakeMessageRepository()
        val peer = PeerId("1234567890abcdef")
        useCase(transport, repo).invoke(ConversationId.Private(peer), "yo", sender)

        val echo = repo.appended.single().second
        assertEquals(DeliveryStatus.Sending, echo.deliveryStatus)
        val sent = transport.privates.single()
        assertEquals(peer, sent.to)
        assertEquals("MSG-1", sent.messageId)
    }

    @Test fun `geohash echoes locally and sends via transport`() = runTest {
        val transport = FakeMessageTransport(); val repo = FakeMessageRepository()
        val gh = GeohashChannel(GeohashLevel.CITY, "u4pruyd")
        val target = ConversationId.Geohash(gh)
        useCase(transport, repo).invoke(target, "loc", sender)

        val echo = repo.appended.single()
        assertEquals(target, echo.first)
        assertEquals("loc", echo.second.content)
        assertTrue(echo.second.isMine)
        assertEquals(1, transport.geos.size)
        assertEquals(gh, transport.geos.single().channel)
    }
}
