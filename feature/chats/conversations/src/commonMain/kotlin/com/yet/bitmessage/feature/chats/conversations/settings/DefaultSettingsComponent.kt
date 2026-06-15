package com.yet.bitmessage.feature.chats.conversations.settings

import com.app.common.decompose.asValue
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.SettingsRepository
import com.app.domain.usecase.PanicWipeUseCase
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yet.bitmessage.feature.chats.conversations.settings.store.SettingsStore
import com.yet.bitmessage.feature.chats.conversations.settings.store.SettingsStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultSettingsComponent(
    componentContext: ComponentContext,
    storeFactory: SettingsStoreFactory,
    private val onClose: () -> Unit,
) : SettingsComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }

    override val model: Value<SettingsComponent.Model> = store.asValue().map { state ->
        SettingsComponent.Model(
            nickname = state.nickname,
            npub = state.npub,
            fingerprint = state.fingerprint,
            isWiping = state.isWiping,
        )
    }

    override fun onNicknameChanged(text: String) = store.accept(SettingsStore.Intent.NicknameChanged(text))

    override fun onPanicWipe() = store.accept(SettingsStore.Intent.PanicWipe)

    override fun onCloseClicked() = onClose()
}

@Inject
internal class DefaultSettingsComponentFactory(
    private val storeFactory: StoreFactory,
    private val settingsRepository: SettingsRepository,
    private val identityRepository: IdentityRepository,
    private val messageRepository: MessageRepository,
    private val contactRepository: ContactRepository,
) : SettingsComponent.Factory {
    override fun create(componentContext: ComponentContext, onClose: () -> Unit): SettingsComponent =
        DefaultSettingsComponent(
            componentContext = componentContext,
            storeFactory = SettingsStoreFactory(
                storeFactory = storeFactory,
                settingsRepository = settingsRepository,
                identityRepository = identityRepository,
                panicWipe = PanicWipeUseCase(messageRepository, contactRepository, identityRepository),
            ),
            onClose = onClose,
        )
}
