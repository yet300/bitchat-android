package com.yet.bitmessage.feature.chats.details

import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.GeohashRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MessageTransport
import com.app.domain.repository.PeerRepository
import com.app.domain.usecase.ResolveReachabilityUseCase
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.app.common.decompose.asValue
import com.yet.bitmessage.feature.chats.details.integration.stateToModel
import com.yet.bitmessage.feature.chats.details.store.ChatStore
import com.yet.bitmessage.feature.chats.details.store.ChatStoreFactory
import com.yet.bitmessage.feature.chats.details.verify.VerifyScanComponent
import dev.zacsweers.metro.Inject
import kotlinx.serialization.Serializable

internal class DefaultChatComponent(
    componentContext: ComponentContext,
    storeFactory: ChatStoreFactory,
    private val verifyScanFactory: VerifyScanComponent.Factory,
    private val onFinished: () -> Unit,
) : ChatComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val verifyScanNavigation = SlotNavigation<VerifyScanConfig>()

    override val model: Value<ChatComponent.Model> = store.asValue().map(stateToModel)

    override val verifyScan: Value<ChildSlot<*, VerifyScanComponent>> =
        childSlot(
            source = verifyScanNavigation,
            serializer = VerifyScanConfig.serializer(),
            handleBackButton = true,
            childFactory = { _, ctx -> verifyScanFactory.create(ctx) { verifyScanNavigation.dismiss() } },
        )

    override fun onDraftChanged(text: String) = store.accept(ChatStore.Intent.DraftChanged(text))

    override fun onSendClicked() = store.accept(ChatStore.Intent.SendClicked)

    override fun onVerifyClicked() = verifyScanNavigation.activate(VerifyScanConfig)

    override fun onDismissVerifyScan() = verifyScanNavigation.dismiss()

    override fun onBackClicked() = onFinished()

    @Serializable
    private data object VerifyScanConfig
}

/** Display title until the chat store provides the real one (nickname resolution etc.). */
private fun ChatConfig.titleFallback(): String = when (val c = conversation) {
    is ChatConfig.Conversation.PublicMesh -> "mesh"
    is ChatConfig.Conversation.Channel -> c.tag
    is ChatConfig.Conversation.Private -> c.peerRaw.take(8)
    is ChatConfig.Conversation.Geohash -> "#${c.geohash}"
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
    private val channelRepository: ChannelRepository,
    private val geohashRepository: GeohashRepository,
    private val verifyScanFactory: VerifyScanComponent.Factory,
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
            targetMessageId = config.targetMessageId,
            messageRepository = messageRepository,
            identityRepository = identityRepository,
            conversationRepository = conversationRepository,
            resolveReachability = ResolveReachabilityUseCase(peerRepository, contactRepository),
            channelRepository = channelRepository,
            contactRepository = contactRepository,
            peerRepository = peerRepository,
            messageTransport = messageTransport,
            geohashRepository = geohashRepository,
        ),
        verifyScanFactory = verifyScanFactory,
        onFinished = onFinished,
    )
}
