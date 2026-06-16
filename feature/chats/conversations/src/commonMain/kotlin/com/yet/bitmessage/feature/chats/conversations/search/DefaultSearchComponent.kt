package com.yet.bitmessage.feature.chats.conversations.search

import com.app.common.decompose.asValue
import com.app.domain.model.ConversationId
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.PeerRepository
import com.app.domain.repository.PlaceGeocoder
import com.app.domain.repository.SearchRepository
import com.app.domain.usecase.ParseGeohashUseCase
import com.app.domain.usecase.SearchUseCase
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yet.bitmessage.feature.chats.conversations.search.store.SearchStore
import com.yet.bitmessage.feature.chats.conversations.search.store.SearchStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultSearchComponent(
    componentContext: ComponentContext,
    storeFactory: SearchStoreFactory,
    private val onResultSelected: (id: ConversationId, targetMessageId: String?) -> Unit,
    private val onClose: () -> Unit,
) : SearchComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }

    override val model: Value<SearchComponent.Model> = store.asValue().map(searchStateToModel)

    override fun onQueryChanged(text: String) = store.accept(SearchStore.Intent.QueryChanged(text))

    override fun onTabSelected(tab: SearchTab) = store.accept(SearchStore.Intent.TabSelected(tab))

    override fun onResultClicked(id: ConversationId) = onResultSelected(id, null)

    override fun onMessageClicked(id: ConversationId, messageId: String) = onResultSelected(id, messageId)

    override fun onCloseClicked() = onClose()
}

@Inject
internal class DefaultSearchComponentFactory(
    private val storeFactory: StoreFactory,
    private val conversationRepository: ConversationRepository,
    private val peerRepository: PeerRepository,
    private val searchRepository: SearchRepository,
    private val placeGeocoder: PlaceGeocoder,
) : SearchComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        onResultSelected: (id: ConversationId, targetMessageId: String?) -> Unit,
        onClose: () -> Unit,
    ): SearchComponent = DefaultSearchComponent(
        componentContext = componentContext,
        storeFactory = SearchStoreFactory(
            storeFactory = storeFactory,
            conversationRepository = conversationRepository,
            peerRepository = peerRepository,
            searchUseCase = SearchUseCase(searchRepository),
            parseGeohash = ParseGeohashUseCase(),
            placeGeocoder = placeGeocoder,
        ),
        onResultSelected = onResultSelected,
        onClose = onClose,
    )
}
