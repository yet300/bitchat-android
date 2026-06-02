@file:OptIn(ExperimentalTime::class)

package com.app.domain.usecase

import com.app.domain.FakeMessageRepository
import com.app.domain.FakeMessageTransport
import com.app.domain.model.Attachment
import com.app.domain.model.AttachmentKind
import com.app.domain.model.ConversationId
import com.app.domain.model.MessageType
import com.app.domain.model.PeerId
import com.app.domain.model.SenderRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class SendAttachmentUseCaseTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    }
    private val sender = SenderRef(PeerId("1111111111111111"), "me")
    private val target = ConversationId.Private(PeerId("1234567890abcdef"))

    private fun useCase(transport: FakeMessageTransport, repo: FakeMessageRepository, max: Long = 1_000) =
        SendAttachmentUseCase(transport, repo, clock = fixedClock, newId = { "MSG-1" }, maxBytes = max)

    @Test fun `rejects attachment over the size limit`() = runTest {
        val transport = FakeMessageTransport(); val repo = FakeMessageRepository()
        val big = Attachment(AttachmentKind.FILE, ref = "/f", sizeBytes = 2_000)
        val result = useCase(transport, repo).invoke(target, big, sender)

        assertTrue(result is AttachmentSendResult.RejectedTooLarge)
        assertTrue(repo.appended.isEmpty())
        assertTrue(transport.attachments.isEmpty())
    }

    @Test fun `sends within limit with echo and progress seed`() = runTest {
        val transport = FakeMessageTransport(); val repo = FakeMessageRepository()
        val img = Attachment(AttachmentKind.IMAGE, ref = "/pic.jpg", sizeBytes = 500)
        val result = useCase(transport, repo).invoke(target, img, sender)

        assertTrue(result is AttachmentSendResult.Sent)
        val echo = repo.appended.single().second
        assertEquals(MessageType.IMAGE, echo.type)
        assertEquals("/pic.jpg", echo.content)
        assertEquals(img, echo.attachment)
        assertTrue(echo.isMine)
        val sent = transport.attachments.single()
        assertEquals("MSG-1", sent.messageId)
        assertEquals(target, sent.target)
    }

    @Test fun `null size is allowed`() = runTest {
        val transport = FakeMessageTransport(); val repo = FakeMessageRepository()
        val a = Attachment(AttachmentKind.AUDIO, ref = "/a.m4a", sizeBytes = null)
        val result = useCase(transport, repo).invoke(target, a, sender)

        assertTrue(result is AttachmentSendResult.Sent)
        assertEquals(MessageType.AUDIO, repo.appended.single().second.type)
    }
}
