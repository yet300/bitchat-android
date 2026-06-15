package com.yet.bitmessage.feature.chats.conversations.settings

import com.app.common.decompose.asValue
import com.app.domain.model.ThemeMode
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.PowRepository
import com.app.domain.repository.SettingsRepository
import com.app.domain.repository.ThemeRepository
import com.app.domain.repository.TorRepository
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
            theme = state.theme,
            torEnabled = state.torEnabled,
            powEnabled = state.powEnabled,
            powDifficulty = state.powDifficulty,
            powLevels = state.powLevels,
        )
    }

    override fun onNicknameChanged(text: String) = store.accept(SettingsStore.Intent.NicknameChanged(text))

    override fun onThemeSelected(mode: ThemeMode) = store.accept(SettingsStore.Intent.ThemeSelected(mode))

    override fun onTorToggled(enabled: Boolean) = store.accept(SettingsStore.Intent.TorToggled(enabled))

    override fun onPowToggled(enabled: Boolean) = store.accept(SettingsStore.Intent.PowToggled(enabled))

    override fun onPowDifficultySelected(difficulty: Int) =
        store.accept(SettingsStore.Intent.PowDifficultySelected(difficulty))

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
    private val themeRepository: ThemeRepository,
    private val torRepository: TorRepository,
    private val powRepository: PowRepository,
) : SettingsComponent.Factory {
    override fun create(componentContext: ComponentContext, onClose: () -> Unit): SettingsComponent =
        DefaultSettingsComponent(
            componentContext = componentContext,
            storeFactory = SettingsStoreFactory(
                storeFactory = storeFactory,
                settingsRepository = settingsRepository,
                identityRepository = identityRepository,
                themeRepository = themeRepository,
                torRepository = torRepository,
                powRepository = powRepository,
                panicWipe = PanicWipeUseCase(messageRepository, contactRepository, identityRepository),
            ),
            onClose = onClose,
        )
}
