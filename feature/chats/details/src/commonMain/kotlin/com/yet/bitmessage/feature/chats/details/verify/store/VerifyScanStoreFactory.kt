package com.yet.bitmessage.feature.chats.details.verify.store

import com.app.domain.repository.CameraPermissionRepository
import com.app.domain.repository.PeerVerificationRepository
import com.app.domain.repository.VerifyScanResult
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch

internal class VerifyScanStoreFactory(
    private val storeFactory: StoreFactory,
    private val cameraPermissionRepository: CameraPermissionRepository,
    private val peerVerificationRepository: PeerVerificationRepository,
) {
    fun create(): VerifyScanStore =
        object : VerifyScanStore,
            Store<VerifyScanStore.Intent, VerifyScanStore.State, Nothing> by storeFactory.create(
                name = "VerifyScanStore",
                initialState = VerifyScanStore.State(),
                bootstrapper = SimpleBootstrapper(VerifyScanStore.Action.Load),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<VerifyScanStore.State, VerifyScanStore.Msg> {
        override fun VerifyScanStore.State.reduce(msg: VerifyScanStore.Msg): VerifyScanStore.State =
            when (msg) {
                is VerifyScanStore.Msg.CameraGranted -> copy(cameraGranted = msg.granted)
                is VerifyScanStore.Msg.ResultLoaded -> copy(result = msg.result)
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<VerifyScanStore.Intent, VerifyScanStore.Action, VerifyScanStore.State, VerifyScanStore.Msg, Nothing>() {

        override fun executeAction(action: VerifyScanStore.Action) {
            when (action) {
                VerifyScanStore.Action.Load -> scope.launch {
                    cameraPermissionRepository.observeGranted()
                        .collect { dispatch(VerifyScanStore.Msg.CameraGranted(it)) }
                }
            }
        }

        override fun executeIntent(intent: VerifyScanStore.Intent) {
            when (intent) {
                VerifyScanStore.Intent.RequestCameraPermission -> scope.launch {
                    cameraPermissionRepository.requestPermission()
                }
                is VerifyScanStore.Intent.QrScanned -> {
                    // Ignore repeat frames once a challenge has already started.
                    if (state().result is VerifyScanResult.Started) return
                    scope.launch {
                        dispatch(VerifyScanStore.Msg.ResultLoaded(peerVerificationRepository.verifyScannedQr(intent.payload)))
                    }
                }
            }
        }
    }
}
