package com.yet.bitmessage.feature.chats.conversations

import com.app.common.decompose.asValue
import com.app.domain.model.ConversationId
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.app.domain.repository.ConversationRepository
import com.yet.bitmessage.feature.chats.conversations.integration.stateToModel
import com.yet.bitmessage.feature.chats.conversations.store.ConversationsStore
import com.yet.bitmessage.feature.chats.conversations.store.ConversationsStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultConversationsComponent(
    componentContext: ComponentContext,
    storeFactory: ConversationsStoreFactory,
    private val onConversationSelected: (ConversationId) -> Unit,
) : ConversationsComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }

    override val model: Value<ConversationsComponent.Model> = store.asValue().map(stateToModel)

    override fun onConversationClicked(id: ConversationId) = onConversationSelected(id)

    override fun onQueryChanged(text: String) =
        store.accept(ConversationsStore.Intent.QueryChanged(text))
}

@Inject
internal class DefaultConversationsComponentFactory(
    private val storeFactory: StoreFactory,
    private val conversationRepository: ConversationRepository,
) : ConversationsComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        onConversationSelected: (ConversationId) -> Unit,
    ): ConversationsComponent = DefaultConversationsComponent(
        componentContext = componentContext,
        storeFactory = ConversationsStoreFactory(storeFactory, conversationRepository),
        onConversationSelected = onConversationSelected,
    )
}
