package com.app.domain.usecase

import com.app.domain.FakeContactRepository
import com.app.domain.FakeMessageRepository
import com.app.domain.FakePeerRepository
import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessCommandUseCaseTest {

    private fun useCase(
        messages: FakeMessageRepository = FakeMessageRepository(),
        contacts: FakeContactRepository = FakeContactRepository(),
        peers: FakePeerRepository = FakePeerRepository(),
    ) = ProcessCommandUseCase(messages, contacts, peers)

    private val parse = ParseCommandUseCase()

    private fun cmd(input: String) = requireNotNull(parse(input))

    @Test fun clear_wipes_the_current_conversation() = runTest {
        val messages = FakeMessageRepository()
        messages.store[ConversationId.PublicMesh] = mutableListOf()
        val result = useCase(messages = messages).invoke(ConversationId.PublicMesh, cmd("/clear"))
        assertEquals(CommandResult.Handled, result)
    }

    @Test fun who_lists_connected_peers() = runTest {
        val peers = FakePeerRepository(
            listOf(
                Peer(id = PeerId("1111111111111111"), nickname = "neo", isConnected = true, isDirect = true),
                Peer(id = PeerId("2222222222222222"), nickname = "off", isConnected = false, isDirect = false),
            ),
        )
        val feedback = assertIs<CommandResult.Feedback>(useCase(peers = peers).invoke(ConversationId.PublicMesh, cmd("/w")))
        assertTrue(feedback.text.contains("neo"))
        assertTrue(!feedback.text.contains("off"))
    }

    @Test fun block_resolves_nickname_and_blocks() = runTest {
        val peerId = PeerId("1111111111111111")
        val peers = FakePeerRepository(listOf(Peer(id = peerId, nickname = "spam", isConnected = true, isDirect = true)))
        val contacts = FakeContactRepository()
        val result = useCase(contacts = contacts, peers = peers).invoke(ConversationId.PublicMesh, cmd("/block spam"))
        assertIs<CommandResult.Feedback>(result)
        assertTrue(peerId in contacts.blocked)
    }

    @Test fun hug_is_sent_as_a_message() = runTest {
        val result = useCase().invoke(ConversationId.PublicMesh, cmd("/hug bob"))
        val send = assertIs<CommandResult.SendAsMessage>(result)
        assertTrue(send.text.contains("bob"))
    }

    @Test fun unknown_command_is_feedback() = runTest {
        val result = useCase().invoke(ConversationId.PublicMesh, cmd("/nope"))
        val feedback = assertIs<CommandResult.Feedback>(result)
        assertTrue(feedback.text.contains("Unknown"))
    }
}
