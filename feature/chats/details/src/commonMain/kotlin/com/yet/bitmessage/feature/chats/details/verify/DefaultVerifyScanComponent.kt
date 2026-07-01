package com.yet.bitmessage.feature.chats.details.verify

import com.app.common.decompose.asValue
import com.app.common.permission.PermissionController
import com.app.domain.repository.PeerVerificationRepository
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yet.bitmessage.feature.chats.details.verify.store.VerifyScanStore
import com.yet.bitmessage.feature.chats.details.verify.store.VerifyScanStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultVerifyScanComponent(
    componentContext: ComponentContext,
    storeFactory: VerifyScanStoreFactory,
    private val onClose: () -> Unit,
) : VerifyScanComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }

    override val model: Value<VerifyScanComponent.Model> = store.asValue().map { state ->
        VerifyScanComponent.Model(cameraGranted = state.cameraGranted, result = state.result)
    }

    override fun onRequestCameraPermission() = store.accept(VerifyScanStore.Intent.RequestCameraPermission)

    override fun onQrScanned(payload: String) = store.accept(VerifyScanStore.Intent.QrScanned(payload))

    override fun onCloseClicked() = onClose()
}

@Inject
internal class DefaultVerifyScanComponentFactory(
    private val storeFactory: StoreFactory,
    private val permissionController: PermissionController,
    private val peerVerificationRepository: PeerVerificationRepository,
) : VerifyScanComponent.Factory {
    override fun create(componentContext: ComponentContext, onClose: () -> Unit): VerifyScanComponent =
        DefaultVerifyScanComponent(
            componentContext = componentContext,
            storeFactory = VerifyScanStoreFactory(
                storeFactory = storeFactory,
                permissionController = permissionController,
                peerVerificationRepository = peerVerificationRepository,
            ),
            onClose = onClose,
        )
}
