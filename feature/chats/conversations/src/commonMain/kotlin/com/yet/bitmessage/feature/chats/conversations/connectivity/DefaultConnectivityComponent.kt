package com.yet.bitmessage.feature.chats.conversations.connectivity

import com.app.common.decompose.asValue
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.TransportKind
import com.app.domain.repository.ConnectivityRepository
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.PeerRepository
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yet.bitmessage.feature.chats.conversations.connectivity.store.ConnectivityStore
import com.yet.bitmessage.feature.chats.conversations.connectivity.store.ConnectivityStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultConnectivityComponent(
    componentContext: ComponentContext,
    storeFactory: ConnectivityStoreFactory,
) : ConnectivityComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }

    override val model: Value<ConnectivityComponent.Model> =
        store.asValue().map { state ->
            ConnectivityComponent.Model(
                statuses = state.statuses,
                // Connected first, then strongest signal; favorite resolved against the favorites set.
                peers = state.peers
                    .sortedWith(compareByDescending<Peer> { it.isConnected }.thenByDescending { it.rssi ?: Int.MIN_VALUE })
                    .map { it.toRow(it.fingerprint in state.favorites) },
            )
        }

    override fun onEnableClicked(kind: TransportKind) =
        store.accept(ConnectivityStore.Intent.Enable(kind))

    override fun onToggleFavorite(peerIdRaw: String) =
        store.accept(ConnectivityStore.Intent.ToggleFavorite(PeerId(peerIdRaw)))

    private fun Peer.toRow(isFavorite: Boolean) = ConnectivityComponent.PeerRow(
        peerIdRaw = id.raw,
        name = nickname.ifBlank { id.raw.take(8) },
        rssi = rssi,
        isDirect = isDirect,
        isConnected = isConnected,
        isVerified = isVerified,
        isFavorite = isFavorite,
    )
}

@Inject
internal class DefaultConnectivityComponentFactory(
    private val storeFactory: StoreFactory,
    private val connectivityRepository: ConnectivityRepository,
    private val peerRepository: PeerRepository,
    private val contactRepository: ContactRepository,
) : ConnectivityComponent.Factory {
    override fun create(componentContext: ComponentContext): ConnectivityComponent =
        DefaultConnectivityComponent(
            componentContext = componentContext,
            storeFactory = ConnectivityStoreFactory(
                storeFactory,
                connectivityRepository,
                peerRepository,
                contactRepository,
            ),
        )
}
