package com.yet.bitmessage.feature.chats.details

import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.GeohashBookmarksRepository
import com.app.domain.repository.GeohashRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MessageTransport
import com.app.domain.repository.NoiseSessionPort
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
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.app.common.decompose.asValue
import com.app.common.decompose.componentCoroutineScope
import com.app.domain.model.Attachment
import com.app.domain.model.ConversationId
import com.yet.bitmessage.feature.chats.details.integration.stateToModel
import com.yet.bitmessage.feature.chats.details.notes.LocationNotesComponent
import com.yet.bitmessage.feature.chats.details.store.ChatStore
import com.yet.bitmessage.feature.chats.details.store.ChatStoreFactory
import com.yet.bitmessage.feature.chats.details.verify.VerifyScanComponent
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

internal class DefaultChatComponent(
    componentContext: ComponentContext,
    storeFactory: ChatStoreFactory,
    private val verifyScanFactory: VerifyScanComponent.Factory,
    private val locationNotesFactory: LocationNotesComponent.Factory,
    private val onFinished: () -> Unit,
    private val onOpenConversation: (ConversationId) -> Unit,
) : ChatComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val sheetNavigation = SlotNavigation<SheetConfig>()

    init {
        componentCoroutineScope().launch {
            store.labels.collect { label ->
                when (label) {
                    is ChatStore.Label.OpenConversation -> {
                        sheetNavigation.dismiss()
                        onOpenConversation(label.id)
                    }
                }
            }
        }
    }

    override val model: Value<ChatComponent.Model> = store.asValue().map(stateToModel)

    override val sheetSlot: Value<ChildSlot<*, ChatComponent.ChatSheetChild>> =
        childSlot(
            source = sheetNavigation,
            serializer = SheetConfig.serializer(),
            handleBackButton = true,
            childFactory = { config, ctx ->
                when (config) {
                    SheetConfig.VerifyScan -> ChatComponent.ChatSheetChild.VerifyScan(
                        verifyScanFactory.create(ctx) { sheetNavigation.dismiss() },
                    )
                    SheetConfig.Participants -> ChatComponent.ChatSheetChild.Participants
                    is SheetConfig.LocationNotes -> ChatComponent.ChatSheetChild.LocationNotes(
                        locationNotesFactory.create(ctx, config.geohash) { sheetNavigation.dismiss() },
                    )
                }
            },
        )

    override fun onDraftChanged(text: String) = store.accept(ChatStore.Intent.DraftChanged(text))

    override fun onSendClicked() = store.accept(ChatStore.Intent.SendClicked)

    override fun onAttachmentPicked(attachment: Attachment) =
        store.accept(ChatStore.Intent.SendAttachment(attachment))

    override fun onCancelTransfer(messageId: String) =
        store.accept(ChatStore.Intent.CancelTransfer(messageId))

    override fun onMentionSelected(nickname: String) =
        store.accept(ChatStore.Intent.MentionSelected(nickname))

    override fun onToggleBookmark() = store.accept(ChatStore.Intent.ToggleBookmark)

    override fun onVerifyClicked() = sheetNavigation.activate(SheetConfig.VerifyScan)

    override fun onParticipantsClicked() = sheetNavigation.activate(SheetConfig.Participants)

    override fun onNotesClicked() {
        val geohash = (store.state.conversationId as? ConversationId.Geohash)?.channel?.geohash ?: return
        sheetNavigation.activate(SheetConfig.LocationNotes(geohash))
    }

    override fun onParticipantSelected(pubkeyHex: String) =
        store.accept(ChatStore.Intent.ParticipantClicked(pubkeyHex))

    override fun onDismissSheet() = sheetNavigation.dismiss()

    override fun onBackClicked() = onFinished()

    @Serializable
    private sealed interface SheetConfig {
        @Serializable
        data object VerifyScan : SheetConfig

        @Serializable
        data object Participants : SheetConfig

        @Serializable
        data class LocationNotes(val geohash: String) : SheetConfig
    }
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
    private val geohashBookmarks: GeohashBookmarksRepository,
    private val noiseSession: NoiseSessionPort,
    private val verifyScanFactory: VerifyScanComponent.Factory,
    private val locationNotesFactory: LocationNotesComponent.Factory,
) : ChatComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        config: ChatConfig,
        onFinished: () -> Unit,
        onOpenConversation: (ConversationId) -> Unit,
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
            geohashBookmarks = geohashBookmarks,
            noiseSession = noiseSession,
        ),
        verifyScanFactory = verifyScanFactory,
        locationNotesFactory = locationNotesFactory,
        onFinished = onFinished,
        onOpenConversation = onOpenConversation,
    )
}
