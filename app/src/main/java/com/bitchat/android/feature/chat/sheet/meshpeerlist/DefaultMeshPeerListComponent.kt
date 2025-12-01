package com.bitchat.android.feature.chat.sheet.meshpeerlist

import androidx.compose.ui.graphics.Color
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.bitchat.android.core.common.asValue
import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.feature.chat.meshpeerlist.integration.stateToModel
import com.bitchat.android.feature.chat.meshpeerlist.store.MeshPeerListStore
import com.bitchat.android.feature.chat.meshpeerlist.store.MeshPeerListStoreFactory
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.ui.colorForPeerSeed
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class DefaultMeshPeerListComponent(
    componentContext: ComponentContext,
    private val parentStore: com.bitchat.android.feature.chat.store.ChatStore,
    private val onDismissCallback: () -> Unit
) : MeshPeerListComponent, ComponentContext by componentContext, KoinComponent {

    private val storeFactory: StoreFactory by inject()
    private val meshService: BluetoothMeshService by inject()
    private val favoritesService: FavoritesPersistenceService by inject()

    private val store = instanceKeeper.getStore {
        MeshPeerListStoreFactory(storeFactory, parentStore).create()
    }

    override val model: Value<MeshPeerListComponent.Model> = store.asValue().map(stateToModel)

    override fun onDismiss() {
        onDismissCallback()
    }

    override fun onSwitchToChannel(channel: String) {
        store.accept(MeshPeerListStore.Intent.SwitchToChannel(channel))
        onDismissCallback()
    }

    override fun onLeaveChannel(channel: String) {
        store.accept(MeshPeerListStore.Intent.LeaveChannel(channel))
    }

    override fun onStartPrivateChat(peerID: String) {
        store.accept(MeshPeerListStore.Intent.StartPrivateChat(peerID))
    }

    override fun onEndPrivateChat() {
        store.accept(MeshPeerListStore.Intent.EndPrivateChat)
    }

    override fun onStartGeohashDM(nostrPubkey: String) {
        store.accept(MeshPeerListStore.Intent.StartGeohashDM(nostrPubkey))
    }

    override fun onToggleFavorite(peerID: String) {
        store.accept(MeshPeerListStore.Intent.ToggleFavorite(peerID))
    }

    // Peer info queries - use Store state and utility functions directly
    override fun isPersonTeleported(nostrPubkey: String): Boolean {
        return store.state.teleportedGeo.contains(nostrPubkey.lowercase())
    }

    override fun colorForNostrPubkey(pubkey: String, isDark: Boolean): Color {
        val seed = "nostr:${pubkey.lowercase()}"
        return colorForPeerSeed(seed, isDark)
    }

    override fun getPeerNoisePublicKeyHex(peerID: String): String? {
        return try {
            meshService.getPeerInfo(peerID)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
        } catch (_: Exception) { null }
    }

    override fun getOfflineFavorites(): List<FavoriteRelationship> {
        return favoritesService.getOurFavorites()
    }

    override fun findNostrPubkey(noiseKey: ByteArray): String? {
        return favoritesService.findNostrPubkey(noiseKey)
    }

    override fun isPeerDirectConnection(peerID: String): Boolean {
        return try {
            meshService.getPeerInfo(peerID)?.isDirectConnection == true
        } catch (_: Exception) { false }
    }

    override fun colorForMeshPeer(peerID: String, isDark: Boolean): Color {
        val seed = "noise:${peerID.lowercase()}"
        return com.bitchat.android.ui.colorForPeerSeed(seed, isDark)
    }
}
