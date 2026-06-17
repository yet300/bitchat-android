package com.yet.bitmessage.feature.chats.conversations.store

import com.app.domain.model.Peer
import com.app.domain.repository.ConnectivityRepository
import com.app.domain.repository.ConversationPrefsRepository
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.PeerRepository
import com.app.domain.usecase.CanonicalConversationKeyUseCase
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
    private val conversationPrefsRepository: ConversationPrefsRepository,
    private val connectivityRepository: ConnectivityRepository,
    private val resolveReachability: ResolveReachabilityUseCase,
    private val canonicalConversationKey: CanonicalConversationKeyUseCase,
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
                is ConversationsStore.Msg.PinnedLoaded -> copy(pinned = msg.pinned)
                is ConversationsStore.Msg.MutedLoaded -> copy(muted = msg.muted)
                is ConversationsStore.Msg.CanonicalKeysLoaded -> copy(canonicalKeys = msg.canonicalKeys)
                is ConversationsStore.Msg.TransportsLoaded -> {
                    val next = copy(transports = msg.transports)
                    // Once everything is resolved, arm the banner again for any future issue.
                    if (next.transportsNeedingAttention.isEmpty()) next.copy(bannerDismissed = false) else next
                }
                ConversationsStore.Msg.BannerDismissed -> copy(bannerDismissed = true)
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
                    // Stable key per row: pin/mute live in Noise-keyed space, but rows are keyed by
                    // the ephemeral mesh peerID. Resolve each row's canonical key so membership is
                    // tested in the same stable space (and survives sessions).
                    scope.launch {
                        conversationRepository.observeConversations()
                            .map { conversations ->
                                buildMap {
                                    conversations.forEach { put(it.id, canonicalConversationKey(it.id)) }
                                }
                            }
                            .collect { dispatch(ConversationsStore.Msg.CanonicalKeysLoaded(it)) }
                    }
                    scope.launch {
                        conversationPrefsRepository.observePinned().collect {
                            dispatch(ConversationsStore.Msg.PinnedLoaded(it))
                        }
                    }
                    scope.launch {
                        conversationPrefsRepository.observeMuted().collect {
                            dispatch(ConversationsStore.Msg.MutedLoaded(it))
                        }
                    }
                    scope.launch {
                        connectivityRepository.observe().collect {
                            dispatch(ConversationsStore.Msg.TransportsLoaded(it))
                        }
                    }
                }
            }
        }

        override fun executeIntent(intent: ConversationsStore.Intent) {
            when (intent) {
                is ConversationsStore.Intent.TogglePin ->
                    scope.launch {
                        val key = canonicalConversationKey(intent.id)
                        conversationPrefsRepository.setPinned(key, key !in state().pinned)
                    }

                is ConversationsStore.Intent.ToggleMute ->
                    scope.launch {
                        val key = canonicalConversationKey(intent.id)
                        conversationPrefsRepository.setMuted(key, key !in state().muted)
                    }

                is ConversationsStore.Intent.DismissBanner ->
                    dispatch(ConversationsStore.Msg.BannerDismissed)
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
