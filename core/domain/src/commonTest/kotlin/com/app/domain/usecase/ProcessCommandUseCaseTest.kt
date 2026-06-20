package com.app.domain.usecase

import com.app.domain.FakeContactRepository
import com.app.domain.FakeMessageRepository
import com.app.domain.FakePeerRepository
import com.app.domain.model.Channel
import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.RetentionPolicy
import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.JoinResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessCommandUseCaseTest {

    private class FakeChannelRepository(
        joined: Set<String> = emptySet(),
        private val protected: Set<String> = emptySet(),
    ) : ChannelRepository {
        val joinedFlow = MutableStateFlow(joined)
        val passwordsSet = mutableListOf<Pair<String, String>>()
        val retentionSet = mutableListOf<Pair<String, RetentionPolicy>>()
        override fun observeJoinedChannels(): Flow<Set<String>> = joinedFlow
        override fun observeChannels(): Flow<List<Channel>> = MutableStateFlow(emptyList())
        override suspend fun join(tag: String, password: String?): JoinResult =
            if (tag in protected && password == null) JoinResult.NeedsPassword else JoinResult.Joined
        override suspend fun leave(tag: String) = Unit
        override suspend fun setPassword(tag: String, password: String) { passwordsSet += tag to password }
        override suspend fun isCreator(tag: String): Boolean = false
        override fun observeRetention(tag: String): Flow<RetentionPolicy> =
            MutableStateFlow(RetentionPolicy.KEEP_ALL)
        override suspend fun setRetention(tag: String, policy: RetentionPolicy) { retentionSet += tag to policy }
    }

    private fun useCase(
        messages: FakeMessageRepository = FakeMessageRepository(),
        channels: ChannelRepository = FakeChannelRepository(),
        contacts: FakeContactRepository = FakeContactRepository(),
        peers: FakePeerRepository = FakePeerRepository(),
    ) = ProcessCommandUseCase(messages, channels, contacts, peers)

    private val parse = ParseCommandUseCase()

    private fun cmd(input: String) = requireNotNull(parse(input))

    @Test fun clear_wipes_the_current_conversation() = runTest {
        val messages = FakeMessageRepository()
        messages.store[ConversationId.PublicMesh] = mutableListOf()
        val result = useCase(messages = messages).invoke(ConversationId.PublicMesh, cmd("/clear"))
        assertEquals(CommandResult.Handled, result)
    }

    @Test fun join_reports_joined() = runTest {
        val result = useCase().invoke(ConversationId.PublicMesh, cmd("/join #kotlin"))
        val feedback = assertIs<CommandResult.Feedback>(result)
        assertTrue(feedback.text.contains("#kotlin"))
    }

    @Test fun pass_in_a_channel_sets_the_password() = runTest {
        val channels = FakeChannelRepository()
        val result = useCase(channels = channels)
            .invoke(ConversationId.Channel("#kotlin"), cmd("/pass hunter2"))
        assertIs<CommandResult.Feedback>(result)
        assertEquals(listOf("#kotlin" to "hunter2"), channels.passwordsSet)
    }

    @Test fun pass_outside_a_channel_is_usage() = runTest {
        val result = useCase().invoke(ConversationId.PublicMesh, cmd("/pass hunter2"))
        val feedback = assertIs<CommandResult.Feedback>(result)
        assertTrue(feedback.text.startsWith("usage"))
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

    @Test fun save_in_a_channel_keeps_messages_locally() = runTest {
        val channels = FakeChannelRepository()
        val result = useCase(channels = channels).invoke(ConversationId.Channel("#kotlin"), cmd("/save"))
        assertIs<CommandResult.Feedback>(result)
        assertEquals(listOf("#kotlin" to RetentionPolicy.KEEP_ALL), channels.retentionSet)
    }

    @Test fun save_outside_a_channel_is_usage() = runTest {
        val channels = FakeChannelRepository()
        val result = useCase(channels = channels).invoke(ConversationId.PublicMesh, cmd("/save"))
        val feedback = assertIs<CommandResult.Feedback>(result)
        assertTrue(feedback.text.startsWith("usage"))
        assertTrue(channels.retentionSet.isEmpty())
    }

    @Test fun transfer_with_nickname_reports_unavailable() = runTest {
        val result = useCase().invoke(ConversationId.Channel("#kotlin"), cmd("/transfer @bob"))
        val feedback = assertIs<CommandResult.Feedback>(result)
        assertTrue(feedback.text.contains("not available"))
    }

    @Test fun transfer_without_nickname_is_usage() = runTest {
        val result = useCase().invoke(ConversationId.Channel("#kotlin"), cmd("/transfer"))
        val feedback = assertIs<CommandResult.Feedback>(result)
        assertTrue(feedback.text.startsWith("usage"))
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
