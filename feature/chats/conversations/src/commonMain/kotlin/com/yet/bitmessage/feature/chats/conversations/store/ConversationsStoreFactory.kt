package com.yet.bitmessage.feature.chats.conversations.store

import com.app.domain.model.Peer
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.PeerRepository
import com.app.domain.usecase.ResolveReachabilityUseCase
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class ConversationsStoreFactory(
    private val storeFactory: StoreFactory,
    private val conversationRepository: ConversationRepository,
    private val peerRepository: PeerRepository,
    private val resolveReachability: ResolveReachabilityUseCase,
) {
    fun create(): ConversationsStore =
        object : ConversationsStore,
            Store<ConversationsStore.Intent, ConversationsStore.State, ConversationsStore.Label> by storeFactory.create(
                name = "ConversationsStore",
                initialState = ConversationsStore.State(),
                bootstrapper = SimpleBootstrapper(ConversationsStore.Action.Subscribe),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<ConversationsStore.State, ConversationsStore.Msg> {
        override fun ConversationsStore.State.reduce(msg: ConversationsStore.Msg): ConversationsStore.State =
            when (msg) {
                is ConversationsStore.Msg.Loaded -> copy(isLoading = false, conversations = msg.conversations)
                is ConversationsStore.Msg.ReachabilityLoaded -> copy(reachability = msg.reachability)
                is ConversationsStore.Msg.NearbyLoaded -> copy(nearby = msg.nearby)
                is ConversationsStore.Msg.QueryChanged -> copy(query = msg.text)
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private inner class ExecutorImpl :
        CoroutineExecutor<ConversationsStore.Intent, ConversationsStore.Action, ConversationsStore.State, ConversationsStore.Msg, ConversationsStore.Label>() {

        override fun executeAction(action: ConversationsStore.Action) {
            when (action) {
                ConversationsStore.Action.Subscribe -> {
                    scope.launch {
                        conversationRepository.observeConversations().collect { conversations ->
                            dispatch(ConversationsStore.Msg.Loaded(conversations))
                        }
                    }
                    // Per-row reachability: re-subscribe whenever the conversation set changes,
                    // combining each id's live reachability into a single map.
                    scope.launch {
                        conversationRepository.observeConversations()
                            .flatMapLatest { conversations ->
                                if (conversations.isEmpty()) {
                                    flowOf(emptyMap())
                                } else {
                                    combine(
                                        conversations.map { c ->
                                            resolveReachability.observe(c.id).map { c.id to it }
                                        },
                                    ) { pairs -> pairs.toMap() }
                                }
                            }
                            .collect { dispatch(ConversationsStore.Msg.ReachabilityLoaded(it)) }
                    }
                    // Nearby rail: peers currently reachable over the mesh (direct first,
                    // strongest signal first), for proximity discovery.
                    scope.launch {
                        peerRepository.observePeers().collect { peers ->
                            dispatch(ConversationsStore.Msg.NearbyLoaded(peers.filter { it.isConnected }.sortedByNearby()))
                        }
                    }
                }
            }
        }

        override fun executeIntent(intent: ConversationsStore.Intent) {
            when (intent) {
                is ConversationsStore.Intent.QueryChanged ->
                    dispatch(ConversationsStore.Msg.QueryChanged(intent.text))
            }
        }
    }
}

/** Direct peers first, then strongest signal, then nickname — for a stable nearby rail. */
private fun List<Peer>.sortedByNearby(): List<Peer> =
    sortedWith(
        compareByDescending<Peer> { it.isDirect }
            .thenByDescending { it.rssi ?: Int.MIN_VALUE }
            .thenBy { it.nickname },
    )
