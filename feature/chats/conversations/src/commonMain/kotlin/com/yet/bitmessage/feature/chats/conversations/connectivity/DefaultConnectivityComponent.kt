package com.yet.bitmessage.feature.chats.conversations.connectivity

import com.app.common.decompose.asValue
import com.app.domain.model.TransportKind
import com.app.domain.repository.ConnectivityRepository
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
        store.asValue().map { ConnectivityComponent.Model(statuses = it.statuses) }

    override fun onEnableClicked(kind: TransportKind) =
        store.accept(ConnectivityStore.Intent.Enable(kind))
}

@Inject
internal class DefaultConnectivityComponentFactory(
    private val storeFactory: StoreFactory,
    private val connectivityRepository: ConnectivityRepository,
) : ConnectivityComponent.Factory {
    override fun create(componentContext: ComponentContext): ConnectivityComponent =
        DefaultConnectivityComponent(
            componentContext = componentContext,
            storeFactory = ConnectivityStoreFactory(storeFactory, connectivityRepository),
        )
}
