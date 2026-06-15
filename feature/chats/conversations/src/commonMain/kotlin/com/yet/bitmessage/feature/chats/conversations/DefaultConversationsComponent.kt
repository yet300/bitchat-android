package com.yet.bitmessage.feature.chats.conversations

import com.app.common.decompose.asValue
import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.app.domain.repository.ConnectivityRepository
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.ConversationPrefsRepository
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.PeerRepository
import com.app.domain.usecase.ResolveReachabilityUseCase
import com.yet.bitmessage.feature.chats.conversations.integration.stateToModel
import com.yet.bitmessage.feature.chats.conversations.store.ConversationsStore
import com.yet.bitmessage.feature.chats.conversations.store.ConversationsStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultConversationsComponent(
    componentContext: ComponentContext,
    storeFactory: ConversationsStoreFactory,
    private val onConversationSelected: (ConversationId) -> Unit,
    private val onConnectivityRequested: () -> Unit,
    private val onSearchRequested: () -> Unit,
) : ConversationsComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }

    override val model: Value<ConversationsComponent.Model> = store.asValue().map(stateToModel)

    override fun onConversationClicked(id: ConversationId) = onConversationSelected(id)

    override fun onNearbyClicked(peer: Peer) = onConversationSelected(ConversationId.Private(peer.id))

    override fun onConnectivityClicked() = onConnectivityRequested()

    override fun onSearchClicked() = onSearchRequested()

    override fun onTogglePin(id: ConversationId) = store.accept(ConversationsStore.Intent.TogglePin(id))

    override fun onToggleMute(id: ConversationId) = store.accept(ConversationsStore.Intent.ToggleMute(id))

    override fun onDismissBanner() = store.accept(ConversationsStore.Intent.DismissBanner)
}

@Inject
internal class DefaultConversationsComponentFactory(
    private val storeFactory: StoreFactory,
    private val conversationRepository: ConversationRepository,
    private val peerRepository: PeerRepository,
    private val contactRepository: ContactRepository,
    private val conversationPrefsRepository: ConversationPrefsRepository,
    private val connectivityRepository: ConnectivityRepository,
) : ConversationsComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        onConversationSelected: (ConversationId) -> Unit,
        onConnectivityRequested: () -> Unit,
        onSearchRequested: () -> Unit,
    ): ConversationsComponent = DefaultConversationsComponent(
        componentContext = componentContext,
        storeFactory = ConversationsStoreFactory(
            storeFactory = storeFactory,
            conversationRepository = conversationRepository,
            peerRepository = peerRepository,
            conversationPrefsRepository = conversationPrefsRepository,
            connectivityRepository = connectivityRepository,
            resolveReachability = ResolveReachabilityUseCase(peerRepository, contactRepository),
        ),
        onConversationSelected = onConversationSelected,
        onConnectivityRequested = onConnectivityRequested,
        onSearchRequested = onSearchRequested,
    )
}
