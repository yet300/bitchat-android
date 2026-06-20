package com.yet.bitmessage.feature.debug.store

import com.app.domain.repository.DebugRepository
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor

internal class DebugStoreFactory(
    private val storeFactory: StoreFactory,
    private val debug: DebugRepository,
) {
    fun create(): DebugStore =
        object : DebugStore,
            Store<DebugStore.Intent, DebugStore.State, Nothing> by storeFactory.create(
                name = "DebugStore",
                initialState = DebugStore.State(),
                bootstrapper = SimpleBootstrapper(DebugStore.Action.Load),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<DebugStore.State, DebugStore.Msg> {
        override fun DebugStore.State.reduce(msg: DebugStore.Msg): DebugStore.State =
            when (msg) {
                is DebugStore.Msg.Loaded -> msg.state
                is DebugStore.Msg.GattServerChanged -> copy(gattServerEnabled = msg.enabled)
                is DebugStore.Msg.GattClientChanged -> copy(gattClientEnabled = msg.enabled)
                is DebugStore.Msg.VerboseChanged -> copy(verboseLogging = msg.enabled)
                is DebugStore.Msg.PacketRelayChanged -> copy(packetRelayEnabled = msg.enabled)
                is DebugStore.Msg.SeenCapacityChanged -> copy(seenPacketCapacity = msg.value)
                is DebugStore.Msg.StatusChanged -> copy(status = msg.status)
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<DebugStore.Intent, DebugStore.Action, DebugStore.State, DebugStore.Msg, Nothing>() {

        override fun executeAction(action: DebugStore.Action) {
            when (action) {
                DebugStore.Action.Load -> dispatch(
                    DebugStore.Msg.Loaded(
                        DebugStore.State(
                            gattServerEnabled = debug.gattServerEnabled(),
                            gattClientEnabled = debug.gattClientEnabled(),
                            verboseLogging = debug.verboseLoggingEnabled(),
                            packetRelayEnabled = debug.packetRelayEnabled(),
                            seenPacketCapacity = debug.seenPacketCapacity(),
                            status = debug.debugStatus(),
                        ),
                    ),
                )
            }
        }

        override fun executeIntent(intent: DebugStore.Intent) {
            when (intent) {
                is DebugStore.Intent.SetGattServer -> {
                    debug.setGattServerEnabled(intent.enabled)
                    dispatch(DebugStore.Msg.GattServerChanged(intent.enabled))
                }
                is DebugStore.Intent.SetGattClient -> {
                    debug.setGattClientEnabled(intent.enabled)
                    dispatch(DebugStore.Msg.GattClientChanged(intent.enabled))
                }
                is DebugStore.Intent.SetVerbose -> {
                    debug.setVerboseLoggingEnabled(intent.enabled)
                    dispatch(DebugStore.Msg.VerboseChanged(intent.enabled))
                }
                is DebugStore.Intent.SetPacketRelay -> {
                    debug.setPacketRelayEnabled(intent.enabled)
                    dispatch(DebugStore.Msg.PacketRelayChanged(intent.enabled))
                }
                is DebugStore.Intent.SetSeenCapacity -> {
                    debug.setSeenPacketCapacity(intent.value)
                    dispatch(DebugStore.Msg.SeenCapacityChanged(intent.value))
                }
                DebugStore.Intent.RefreshStatus -> dispatch(DebugStore.Msg.StatusChanged(debug.debugStatus()))
            }
        }
    }
}
