package com.bitchat.android.feature.chat.meshpeerlist.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshEventBus
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.GeohashViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class MeshPeerListStoreFactory(
    private val storeFactory: StoreFactory
) : KoinComponent {

    // TODO: Remove ChatViewModel once all state is migrated to services
    private val chatViewModel: ChatViewModel by inject()
    private val meshService: BluetoothMeshService by inject()
    private val favoritesService: FavoritesPersistenceService by inject()
    private val meshEventBus: MeshEventBus by inject()
    private val locationChannelManager: LocationChannelManager by inject()
    private val geohashViewModel: GeohashViewModel by inject()

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
                    chatViewModel.switchToChannel(intent.channel)
                    publish(MeshPeerListStore.Label.ChannelSwitched(intent.channel))
                }
                is MeshPeerListStore.Intent.LeaveChannel -> {
                    chatViewModel.leaveChannel(intent.channel)
                }
                is MeshPeerListStore.Intent.StartPrivateChat -> {
                    chatViewModel.startPrivateChat(intent.peerID)
                    publish(MeshPeerListStore.Label.PrivateChatStarted(intent.peerID))
                }
                MeshPeerListStore.Intent.EndPrivateChat -> {
                    chatViewModel.endPrivateChat()
                }
                is MeshPeerListStore.Intent.StartGeohashDM -> {
                    chatViewModel.startGeohashDM(intent.nostrPubkey)
                }
                is MeshPeerListStore.Intent.ToggleFavorite -> {
                    chatViewModel.toggleFavorite(intent.peerID)
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
            
            // Geohash state from GeohashViewModel
            scope.launch {
                geohashViewModel.geohashPeople.collectLatest { people ->
                    dispatch(MeshPeerListStore.Msg.GeohashPeopleUpdated(people))
                }
            }
            
            // TODO: These still need ChatViewModel - migrate when ChatState is refactored
            scope.launch {
                chatViewModel.joinedChannels.collectLatest { channels ->
                    dispatch(MeshPeerListStore.Msg.JoinedChannelsUpdated(channels))
                }
            }
            scope.launch {
                chatViewModel.currentChannel.collectLatest { channel ->
                    dispatch(MeshPeerListStore.Msg.CurrentChannelChanged(channel))
                }
            }
            scope.launch {
                chatViewModel.unreadChannelMessages.collectLatest { unread ->
                    dispatch(MeshPeerListStore.Msg.UnreadChannelMessagesUpdated(unread))
                }
            }
            scope.launch {
                chatViewModel.selectedPrivateChatPeer.collectLatest { peer ->
                    dispatch(MeshPeerListStore.Msg.SelectedPrivatePeerChanged(peer))
                }
            }
            scope.launch {
                chatViewModel.unreadPrivateMessages.collectLatest { unread ->
                    dispatch(MeshPeerListStore.Msg.UnreadPrivateMessagesUpdated(unread))
                }
            }
            scope.launch {
                chatViewModel.privateChats.collectLatest { chats ->
                    dispatch(MeshPeerListStore.Msg.PrivateChatsUpdated(chats))
                }
            }
            scope.launch {
                chatViewModel.teleportedGeo.collectLatest { geo ->
                    dispatch(MeshPeerListStore.Msg.TeleportedGeoUpdated(geo))
                }
            }
            scope.launch {
                chatViewModel.peerNicknames.collectLatest { nicknames ->
                    dispatch(MeshPeerListStore.Msg.PeerNicknamesUpdated(nicknames))
                }
            }
            scope.launch {
                chatViewModel.peerRSSI.collectLatest { rssi ->
                    dispatch(MeshPeerListStore.Msg.PeerRSSIUpdated(rssi))
                }
            }
            scope.launch {
                chatViewModel.peerDirect.collectLatest { direct ->
                    dispatch(MeshPeerListStore.Msg.PeerDirectUpdated(direct))
                }
            }
            scope.launch {
                chatViewModel.peerFingerprints.collectLatest { fingerprints ->
                    dispatch(MeshPeerListStore.Msg.PeerFingerprintsUpdated(fingerprints))
                }
            }
            scope.launch {
                chatViewModel.peerSessionStates.collectLatest { states ->
                    dispatch(MeshPeerListStore.Msg.PeerSessionStatesUpdated(states))
                }
            }
            scope.launch {
                chatViewModel.favoritePeers.collectLatest { favorites ->
                    dispatch(MeshPeerListStore.Msg.FavoritePeersUpdated(favorites))
                }
            }
            scope.launch {
                chatViewModel.nickname.collectLatest { nick ->
                    dispatch(MeshPeerListStore.Msg.NicknameChanged(nick))
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
