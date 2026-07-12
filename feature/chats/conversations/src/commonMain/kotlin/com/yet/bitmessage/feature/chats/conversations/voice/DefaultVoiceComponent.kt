package com.yet.bitmessage.feature.chats.conversations.voice

import com.app.common.decompose.asValue
import com.app.common.permission.AppPermission
import com.app.common.permission.PermissionController
import com.app.domain.repository.VoiceRepository
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.yet.bitmessage.feature.chats.conversations.voice.store.VoiceStore
import com.yet.bitmessage.feature.chats.conversations.voice.store.VoiceStoreFactory
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class DefaultVoiceComponent(
    componentContext: ComponentContext,
    storeFactory: VoiceStoreFactory,
    private val permissionController: PermissionController,
    private val onClose: () -> Unit,
) : VoiceComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }

    override val model: Value<VoiceComponent.Model> = store.asValue().map { state ->
        VoiceComponent.Model(
            received = state.received.map { VoiceComponent.ReceivedBurst(it.peerId, it.durationMs) },
        )
    }

    override val playback: Flow<List<ByteArray>> =
        store.labels.map { (it as VoiceStore.Label.Play).frames }

    override fun onBurstCaptured(frames: List<ByteArray>, durationMs: Int) =
        store.accept(VoiceStore.Intent.Send(frames, durationMs))

    override suspend fun requestMicrophonePermission(): Boolean =
        permissionController.requestPermission(AppPermission.Microphone)

    override fun onCloseClicked() = onClose()
}

@Inject
internal class DefaultVoiceComponentFactory(
    private val storeFactory: StoreFactory,
    private val voiceRepository: VoiceRepository,
    private val permissionController: PermissionController,
) : VoiceComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        onClose: () -> Unit,
    ): VoiceComponent = DefaultVoiceComponent(
        componentContext = componentContext,
        storeFactory = VoiceStoreFactory(storeFactory, voiceRepository),
        permissionController = permissionController,
        onClose = onClose,
    )
}
