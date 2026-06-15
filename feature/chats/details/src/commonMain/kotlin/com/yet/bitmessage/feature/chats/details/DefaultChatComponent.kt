package com.yet.bitmessage.feature.chats.details

import com.app.domain.repository.ContactRepository
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MessageTransport
import com.app.domain.repository.PeerRepository
import com.app.domain.usecase.ResolveReachabilityUseCase
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.app.common.decompose.asValue
import com.yet.bitmessage.feature.chats.details.integration.stateToModel
import com.yet.bitmessage.feature.chats.details.store.ChatStore
import com.yet.bitmessage.feature.chats.details.store.ChatStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultChatComponent(
    componentContext: ComponentContext,
    storeFactory: ChatStoreFactory,
    private val onFinished: () -> Unit,
) : ChatComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }

    override val model: Value<ChatComponent.Model> = store.asValue().map(stateToModel)

    override fun onDraftChanged(text: String) = store.accept(ChatStore.Intent.DraftChanged(text))

    override fun onSendClicked() = store.accept(ChatStore.Intent.SendClicked)

    override fun onBackClicked() = onFinished()
}

/** Display title until the chat store provides the real one (nickname resolution etc.). */
private fun ChatConfig.titleFallback(): String = when (this) {
    is ChatConfig.PublicMesh -> "mesh"
    is ChatConfig.Channel -> tag
    is ChatConfig.Private -> peerRaw.take(8)
    is ChatConfig.Geohash -> "#$geohash"
}

@Inject
internal class DefaultChatComponentFactory(
    private val storeFactory: StoreFactory,
    private val messageRepository: MessageRepository,
    private val identityRepository: IdentityRepository,
    private val messageTransport: MessageTransport,
    private val conversationRepository: ConversationRepository,
    private val peerRepository: PeerRepository,
    private val contactRepository: ContactRepository,
) : ChatComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        config: ChatConfig,
        onFinished: () -> Unit,
    ): ChatComponent = DefaultChatComponent(
        componentContext = componentContext,
        storeFactory = ChatStoreFactory(
            storeFactory = storeFactory,
            conversationId = config.toConversationId(),
            title = config.titleFallback(),
            messageRepository = messageRepository,
            identityRepository = identityRepository,
            conversationRepository = conversationRepository,
            resolveReachability = ResolveReachabilityUseCase(peerRepository, contactRepository),
            messageTransport = messageTransport,
        ),
        onFinished = onFinished,
    )
}
