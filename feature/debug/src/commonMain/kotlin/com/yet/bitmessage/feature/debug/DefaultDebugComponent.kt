package com.yet.bitmessage.feature.debug

import com.app.common.decompose.asValue
import com.app.domain.repository.DebugRepository
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yet.bitmessage.feature.debug.integration.stateToModel
import com.yet.bitmessage.feature.debug.store.DebugStore
import com.yet.bitmessage.feature.debug.store.DebugStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultDebugComponent(
    componentContext: ComponentContext,
    storeFactory: DebugStoreFactory,
    private val onClose: () -> Unit,
) : DebugComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }

    override val model: Value<DebugComponent.Model> = store.asValue().map(stateToModel)

    override fun onGattServerToggled(enabled: Boolean) = store.accept(DebugStore.Intent.SetGattServer(enabled))
    override fun onGattClientToggled(enabled: Boolean) = store.accept(DebugStore.Intent.SetGattClient(enabled))
    override fun onVerboseToggled(enabled: Boolean) = store.accept(DebugStore.Intent.SetVerbose(enabled))
    override fun onPacketRelayToggled(enabled: Boolean) = store.accept(DebugStore.Intent.SetPacketRelay(enabled))
    override fun onSeenCapacityChanged(value: Int) = store.accept(DebugStore.Intent.SetSeenCapacity(value))
    override fun onRefreshStatus() = store.accept(DebugStore.Intent.RefreshStatus)
    override fun onCloseClicked() = onClose()
}

@Inject
internal class DefaultDebugComponentFactory(
    private val storeFactory: StoreFactory,
    private val debugRepository: DebugRepository,
) : DebugComponent.Factory {
    override fun create(componentContext: ComponentContext, onClose: () -> Unit): DebugComponent =
        DefaultDebugComponent(
            componentContext = componentContext,
            storeFactory = DebugStoreFactory(storeFactory, debugRepository),
            onClose = onClose,
        )
}
