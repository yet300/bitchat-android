package com.yet.bitmessage.feature.chats.details.store

import com.app.domain.model.ConversationId
import com.app.domain.model.SenderRef
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MessageTransport
import com.app.domain.usecase.MarkConversationReadUseCase
import com.app.domain.usecase.SendMessageUseCase
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch

internal class ChatStoreFactory(
    private val storeFactory: StoreFactory,
    private val conversationId: ConversationId,
    private val title: String,
    private val messageRepository: MessageRepository,
    private val identityRepository: IdentityRepository,
    private val conversationRepository: ConversationRepository,
    messageTransport: MessageTransport,
) {
    private val sendMessage = SendMessageUseCase(messageTransport, messageRepository)
    private val markRead =
        MarkConversationReadUseCase(conversationRepository, messageRepository, messageTransport)

    fun create(): ChatStore =
        object : ChatStore,
            Store<ChatStore.Intent, ChatStore.State, ChatStore.Label> by storeFactory.create(
                name = "ChatStore",
                initialState = ChatStore.State(conversationId = conversationId, title = title),
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
                    scope.launch {
                        val me = identityRepository.myIdentity()
                        val sender = SenderRef(peerId = me.peerId, displayName = me.nickname)
                        sendMessage(target = conversationId, content = text, sender = sender)
                    }
                }
            }
        }
    }
}
