package com.bitchat.android.feature.chat.meshpeerlist.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshEventBus
import com.bitchat.android.nostr.GeohashRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class MeshPeerListStoreFactory(
    private val storeFactory: StoreFactory,
    private val parentStore: com.bitchat.android.feature.chat.store.ChatStore
) : KoinComponent {

    private val meshService: BluetoothMeshService by inject()
    private val favoritesService: FavoritesPersistenceService by inject()
    private val meshEventBus: MeshEventBus by inject()
    private val locationChannelManager: LocationChannelManager by inject()
    private val geohashRepository: GeohashRepository by inject()
    private val dataManager: com.bitchat.android.ui.DataManager by inject()
    private val fingerprintManager: com.bitchat.android.mesh.PeerFingerprintManager by inject()

    fun create(): MeshPeerListStore =
        object : MeshPeerListStore, Store<MeshPeerListStore.Intent, MeshPeerListStore.State, MeshPeerListStore.Label>
        by storeFactory.create(
            name = "MeshPeerListStore",
            initialState = MeshPeerListStore.State(
                myPeerID = meshService.myPeerID
            ),
            bootstrapper = BootstrapperImpl(),
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl
        ) {}

    private sealed interface Action {
        data object SubscribeToFlows : Action
    }

    private inner class BootstrapperImpl : CoroutineBootstrapper<Action>() {
        override fun invoke() {
            dispatch(Action.SubscribeToFlows)
        }
    }

    private inner class ExecutorImpl : CoroutineExecutor<MeshPeerListStore.Intent, Action, MeshPeerListStore.State, MeshPeerListStore.Msg, MeshPeerListStore.Label>() {
        override fun executeAction(action: Action) {
            when (action) {
                Action.SubscribeToFlows -> subscribeToViewModelFlows()
            }
        }

        override fun executeIntent(intent: MeshPeerListStore.Intent) {
            when (intent) {
                is MeshPeerListStore.Intent.SwitchToChannel -> {
                    // Delegate to parent ChatStore
                    parentStore.accept(com.bitchat.android.feature.chat.store.ChatStore.Intent.SwitchToChannel(intent.channel))
                    publish(MeshPeerListStore.Label.ChannelSwitched(intent.channel))
                }
                is MeshPeerListStore.Intent.LeaveChannel -> {
                    parentStore.accept(com.bitchat.android.feature.chat.store.ChatStore.Intent.LeaveChannel(intent.channel))
                }
                is MeshPeerListStore.Intent.StartPrivateChat -> {
                    parentStore.accept(com.bitchat.android.feature.chat.store.ChatStore.Intent.StartPrivateChat(intent.peerID))
                    publish(MeshPeerListStore.Label.PrivateChatStarted(intent.peerID))
                }
                MeshPeerListStore.Intent.EndPrivateChat -> {
                    parentStore.accept(com.bitchat.android.feature.chat.store.ChatStore.Intent.EndPrivateChat)
                }
                is MeshPeerListStore.Intent.StartGeohashDM -> {
                    parentStore.accept(com.bitchat.android.feature.chat.store.ChatStore.Intent.StartGeohashDM(intent.nostrPubkey))
                }
                is MeshPeerListStore.Intent.ToggleFavorite -> {
                    parentStore.accept(com.bitchat.android.feature.chat.store.ChatStore.Intent.ToggleFavorite(intent.peerID))
                }
            }
        }

        private fun subscribeToViewModelFlows() {
            // Connected peers from MeshEventBus
            scope.launch {
                meshEventBus.connectedPeers.collectLatest { peers ->
                    dispatch(MeshPeerListStore.Msg.ConnectedPeersUpdated(peers))
                }
            }
            
            // Location channel state from LocationChannelManager
            scope.launch {
                locationChannelManager.selectedChannel.collectLatest { channel ->
                    dispatch(MeshPeerListStore.Msg.SelectedLocationChannelChanged(channel))
                }
            }
            scope.launch {
                locationChannelManager.teleported.collectLatest { teleported ->
                    dispatch(MeshPeerListStore.Msg.TeleportedStateChanged(teleported))
                }
            }
            
            // Geohash state from GeohashRepository (direct service access)
            scope.launch {
                geohashRepository.geohashPeople.collectLatest { people ->
                    dispatch(MeshPeerListStore.Msg.GeohashPeopleUpdated(people))
                }
            }
            
            // Subscribe to parent ChatStore state changes
            scope.launch {
                parentStore.stateFlow.collectLatest { parentState ->
                    dispatch(MeshPeerListStore.Msg.JoinedChannelsUpdated(parentState.joinedChannels))
                    dispatch(MeshPeerListStore.Msg.CurrentChannelChanged(parentState.currentChannel))
                    dispatch(MeshPeerListStore.Msg.UnreadChannelMessagesUpdated(parentState.unreadChannelMessages))
                    dispatch(MeshPeerListStore.Msg.SelectedPrivatePeerChanged(parentState.selectedPrivateChatPeer))
                    dispatch(MeshPeerListStore.Msg.UnreadPrivateMessagesUpdated(parentState.unreadPrivateMessages))
                    dispatch(MeshPeerListStore.Msg.PrivateChatsUpdated(parentState.privateChats))
                    dispatch(MeshPeerListStore.Msg.TeleportedGeoUpdated(parentState.teleportedGeo))
                    dispatch(MeshPeerListStore.Msg.PeerNicknamesUpdated(parentState.peerNicknames))
                    dispatch(MeshPeerListStore.Msg.PeerRSSIUpdated(parentState.peerRSSI))
                    dispatch(MeshPeerListStore.Msg.PeerDirectUpdated(parentState.peerDirect))
                    dispatch(MeshPeerListStore.Msg.PeerFingerprintsUpdated(parentState.peerFingerprints))
                    dispatch(MeshPeerListStore.Msg.PeerSessionStatesUpdated(parentState.peerSessionStates))
                    dispatch(MeshPeerListStore.Msg.FavoritePeersUpdated(parentState.favoritePeers))
                    dispatch(MeshPeerListStore.Msg.NicknameChanged(parentState.nickname))
                }
            }
            // Load offline favorites
            scope.launch {
                val offlineFavs = favoritesService.getOurFavorites()
                dispatch(MeshPeerListStore.Msg.OfflineFavoritesUpdated(offlineFavs))
            }
        }
    }

    private object ReducerImpl : Reducer<MeshPeerListStore.State, MeshPeerListStore.Msg> {
        override fun MeshPeerListStore.State.reduce(msg: MeshPeerListStore.Msg): MeshPeerListStore.State =
            when (msg) {
                is MeshPeerListStore.Msg.ConnectedPeersUpdated -> copy(connectedPeers = msg.peers)
                is MeshPeerListStore.Msg.JoinedChannelsUpdated -> copy(joinedChannels = msg.channels)
                is MeshPeerListStore.Msg.CurrentChannelChanged -> copy(currentChannel = msg.channel)
                is MeshPeerListStore.Msg.UnreadChannelMessagesUpdated -> copy(unreadChannelMessages = msg.unread)
                is MeshPeerListStore.Msg.SelectedPrivatePeerChanged -> copy(selectedPrivatePeer = msg.peer)
                is MeshPeerListStore.Msg.UnreadPrivateMessagesUpdated -> copy(unreadPrivateMessages = msg.unread)
                is MeshPeerListStore.Msg.PrivateChatsUpdated -> copy(privateChats = msg.chats)
                is MeshPeerListStore.Msg.SelectedLocationChannelChanged -> copy(selectedLocationChannel = msg.channel)
                is MeshPeerListStore.Msg.GeohashPeopleUpdated -> copy(geohashPeople = msg.people)
                is MeshPeerListStore.Msg.TeleportedStateChanged -> copy(isTeleported = msg.teleported)
                is MeshPeerListStore.Msg.TeleportedGeoUpdated -> copy(teleportedGeo = msg.geo)
                is MeshPeerListStore.Msg.PeerNicknamesUpdated -> copy(peerNicknames = msg.nicknames)
                is MeshPeerListStore.Msg.PeerRSSIUpdated -> copy(peerRSSI = msg.rssi)
                is MeshPeerListStore.Msg.PeerDirectUpdated -> copy(peerDirect = msg.direct)
                is MeshPeerListStore.Msg.PeerFingerprintsUpdated -> copy(peerFingerprints = msg.fingerprints)
                is MeshPeerListStore.Msg.PeerSessionStatesUpdated -> copy(peerSessionStates = msg.states)
                is MeshPeerListStore.Msg.FavoritePeersUpdated -> copy(favoritePeers = msg.favorites)
                is MeshPeerListStore.Msg.OfflineFavoritesUpdated -> copy(offlineFavorites = msg.favorites)
                is MeshPeerListStore.Msg.NicknameChanged -> copy(nickname = msg.nickname)
                is MeshPeerListStore.Msg.MyPeerIDUpdated -> copy(myPeerID = msg.peerID)
            }
    }
}
