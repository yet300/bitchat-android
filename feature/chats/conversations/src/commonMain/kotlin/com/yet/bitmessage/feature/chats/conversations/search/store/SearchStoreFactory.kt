package com.yet.bitmessage.feature.chats.conversations.search.store

import com.app.domain.model.SearchResults
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.PeerRepository
import com.app.domain.repository.PlaceGeocoder
import com.app.domain.usecase.ParseGeohashUseCase
import com.app.domain.usecase.SearchUseCase
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

internal class SearchStoreFactory(
    private val storeFactory: StoreFactory,
    private val conversationRepository: ConversationRepository,
    private val peerRepository: PeerRepository,
    private val searchUseCase: SearchUseCase,
    private val parseGeohash: ParseGeohashUseCase,
    private val placeGeocoder: PlaceGeocoder,
) {
    fun create(): SearchStore =
        object : SearchStore,
            Store<SearchStore.Intent, SearchStore.State, SearchStore.Label> by storeFactory.create(
                name = "SearchStore",
                initialState = SearchStore.State(),
                bootstrapper = SimpleBootstrapper(SearchStore.Action.Subscribe),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<SearchStore.State, SearchStore.Msg> {
        override fun SearchStore.State.reduce(msg: SearchStore.Msg): SearchStore.State =
            when (msg) {
                is SearchStore.Msg.QueryChanged -> copy(query = msg.text, parsedGeo = msg.geo)
                is SearchStore.Msg.TabSelected -> copy(tab = msg.tab)
                is SearchStore.Msg.ConversationsLoaded -> copy(conversations = msg.conversations)
                is SearchStore.Msg.PeersLoaded -> copy(peers = msg.peers)
                is SearchStore.Msg.ResultsLoaded -> copy(results = msg.results)
                is SearchStore.Msg.GeocodeLoaded -> copy(geocodedGeo = msg.geo)
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private inner class ExecutorImpl :
        CoroutineExecutor<SearchStore.Intent, SearchStore.Action, SearchStore.State, SearchStore.Msg, SearchStore.Label>() {

        // Drives the suspending message/contact/channel search (the Chats and People-peer tabs
        // filter the live flows client-side; only these three sources hit the repository).
        private val queryFlow = MutableStateFlow("")

        override fun executeAction(action: SearchStore.Action) {
            when (action) {
                SearchStore.Action.Subscribe -> {
                    scope.launch {
                        conversationRepository.observeConversations().collect {
                            dispatch(SearchStore.Msg.ConversationsLoaded(it))
                        }
                    }
                    scope.launch {
                        peerRepository.observePeers().collect {
                            dispatch(SearchStore.Msg.PeersLoaded(it.filter { peer -> peer.isConnected }))
                        }
                    }
                    scope.launch {
                        queryFlow
                            .debounce(SEARCH_DEBOUNCE_MS)
                            .mapLatest { query ->
                                if (query.isBlank()) SearchResults.EMPTY else searchUseCase(query)
                            }
                            .collect { dispatch(SearchStore.Msg.ResultsLoaded(it)) }
                    }
                    // Geo tab: a non-geohash query is treated as a place name and forward-geocoded
                    // (debounced; the instant geohash parse on QueryChanged takes precedence).
                    scope.launch {
                        queryFlow
                            .debounce(SEARCH_DEBOUNCE_MS)
                            .mapLatest { query ->
                                if (query.isBlank() || parseGeohash(query) != null) null
                                else placeGeocoder.toGeohash(query)
                            }
                            .collect { dispatch(SearchStore.Msg.GeocodeLoaded(it)) }
                    }
                }
            }
        }

        override fun executeIntent(intent: SearchStore.Intent) {
            when (intent) {
                is SearchStore.Intent.QueryChanged -> {
                    dispatch(SearchStore.Msg.QueryChanged(intent.text, parseGeohash(intent.text)))
                    queryFlow.value = intent.text
                }
                is SearchStore.Intent.TabSelected -> dispatch(SearchStore.Msg.TabSelected(intent.tab))
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 200L
    }
}
