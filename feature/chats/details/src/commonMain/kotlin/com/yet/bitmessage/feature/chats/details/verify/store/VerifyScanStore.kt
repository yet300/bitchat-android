package com.yet.bitmessage.feature.chats.details.verify.store

import com.app.domain.repository.VerifyScanResult
import com.arkivanov.mvikotlin.core.store.Store

internal interface VerifyScanStore :
    Store<VerifyScanStore.Intent, VerifyScanStore.State, Nothing> {

    data class State(
        val cameraGranted: Boolean = false,
        val result: VerifyScanResult? = null,
    )

    sealed interface Intent {
        data object RequestCameraPermission : Intent
        data class QrScanned(val payload: String) : Intent
    }

    sealed interface Action {
        data object Load : Action
    }

    sealed interface Msg {
        data class CameraGranted(val granted: Boolean) : Msg
        data class ResultLoaded(val result: VerifyScanResult?) : Msg
    }
}
