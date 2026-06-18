package com.yet.bitmessage.feature.chats.details.store

import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.app.domain.model.PeerId
import com.app.domain.model.SenderRef
import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.GeohashRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MessageTransport
import com.app.domain.repository.PeerRepository
import com.app.domain.usecase.ChatCommand
import com.app.domain.usecase.CommandResult
import com.app.domain.usecase.MarkConversationReadUseCase
import com.app.domain.usecase.ParseCommandUseCase
import com.app.domain.usecase.ProcessCommandUseCase
import com.app.domain.usecase.ResolveReachabilityUseCase
import com.app.domain.usecase.SendMessageUseCase
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class ChatStoreFactory(
    private val storeFactory: StoreFactory,
    private val conversationId: ConversationId,
    private val title: String,
    private val targetMessageId: String? = null,
    private val messageRepository: MessageRepository,
    private val identityRepository: IdentityRepository,
    private val conversationRepository: ConversationRepository,
    private val resolveReachability: ResolveReachabilityUseCase,
    private val contactRepository: ContactRepository,
    private val geohashRepository: GeohashRepository,
    channelRepository: ChannelRepository,
    peerRepository: PeerRepository,
    messageTransport: MessageTransport,
) {
    private val sendMessage = SendMessageUseCase(messageTransport, messageRepository)
    private val markRead =
        MarkConversationReadUseCase(conversationRepository, messageRepository, messageTransport)
    private val parseCommand = ParseCommandUseCase()
    private val processCommand =
        ProcessCommandUseCase(messageRepository, channelRepository, contactRepository, peerRepository)

    fun create(): ChatStore =
        object : ChatStore,
            Store<ChatStore.Intent, ChatStore.State, ChatStore.Label> by storeFactory.create(
                name = "ChatStore",
                initialState = ChatStore.State(
                    conversationId = conversationId,
                    title = title,
                    targetMessageId = targetMessageId,
                ),
                bootstrapper = SimpleBootstrapper(ChatStore.Action.Subscribe),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<ChatStore.State, ChatStore.Msg> {
        override fun ChatStore.State.reduce(msg: ChatStore.Msg): ChatStore.State =
            when (msg) {
                is ChatStore.Msg.Loaded -> copy(isLoading = false, messages = msg.messages)
                is ChatStore.Msg.DraftChanged -> copy(draft = msg.text)
                is ChatStore.Msg.TitleResolved -> copy(title = msg.title)
                is ChatStore.Msg.ReachabilityChanged -> copy(reachability = msg.reachability)
                is ChatStore.Msg.VerifiedChanged -> copy(isVerified = msg.verified)
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<ChatStore.Intent, ChatStore.Action, ChatStore.State, ChatStore.Msg, ChatStore.Label>() {

        override fun executeAction(action: ChatStore.Action) {
            when (action) {
                ChatStore.Action.Subscribe -> {
                    scope.launch {
                        messageRepository.observeMessages(conversationId).collect { messages ->
                            dispatch(ChatStore.Msg.Loaded(messages))
                        }
                    }
                    // Resolve the display title from the chat-list aggregate (nickname / channel
                    // name), falling back to the initial id-derived label until it appears.
                    scope.launch {
                        conversationRepository.observeConversations().collect { conversations ->
                            conversations.firstOrNull { it.id == conversationId }
                                ?.title
                                ?.takeIf { it.isNotBlank() }
                                ?.let { dispatch(ChatStore.Msg.TitleResolved(it)) }
                        }
                    }
                    // Live reachability for the header (Nearby / via Nostr / Offline).
                    scope.launch {
                        resolveReachability.observe(conversationId).collect {
                            dispatch(ChatStore.Msg.ReachabilityChanged(it))
                        }
                    }
                    // Verified-trust indicator for a DM keyed by a stable Noise key.
                    val privateId = conversationId as? ConversationId.Private
                    if (privateId != null && privateId.peer.kind == PeerId.Kind.NOISE_STABLE) {
                        scope.launch {
                            contactRepository.observeVerified(privateId.peer.raw).collect {
                                dispatch(ChatStore.Msg.VerifiedChanged(it))
                            }
                        }
                    }
                    // Geo chats: select the location channel so the repository subscribes to the
                    // geohash's Nostr events and ingests them into this timeline (incoming receive).
                    if (conversationId is ConversationId.Geohash) {
                        scope.launch { runCatching { geohashRepository.select(conversationId) } }
                    }
                    // Reset unread count and (private chats) flush read receipts; best-effort.
                    scope.launch { runCatching { markRead(conversationId) } }
                }
            }
        }

        override fun executeIntent(intent: ChatStore.Intent) {
            when (intent) {
                is ChatStore.Intent.DraftChanged -> dispatch(ChatStore.Msg.DraftChanged(intent.text))

                ChatStore.Intent.SendClicked -> {
                    val text = state().draft
                    if (text.isBlank()) return
                    dispatch(ChatStore.Msg.DraftChanged(""))
                    val command = parseCommand(text)
                    if (command != null) scope.launch { runCommand(command) }
                    else scope.launch { send(text) }
                }
            }
        }

        private suspend fun send(text: String) {
            val me = identityRepository.myIdentity()
            val sender = SenderRef(peerId = me.peerId, displayName = me.nickname)
            sendMessage(target = conversationId, content = text, sender = sender)
        }

        private suspend fun runCommand(command: ChatCommand) {
            when (val result = processCommand(conversationId, command)) {
                is CommandResult.Feedback -> messageRepository.append(conversationId, systemMessage(result.text))
                is CommandResult.SendAsMessage -> send(result.text)
                CommandResult.Handled -> Unit
            }
        }

        /** Local-only system line in the timeline (not sent over any transport). */
        @OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
        private fun systemMessage(text: String): BitMessage = BitMessage(
            id = Uuid.random().toString().uppercase(),
            conversationId = conversationId,
            sender = SenderRef.SYSTEM,
            content = text,
            timestamp = Clock.System.now(),
        )
    }
}
