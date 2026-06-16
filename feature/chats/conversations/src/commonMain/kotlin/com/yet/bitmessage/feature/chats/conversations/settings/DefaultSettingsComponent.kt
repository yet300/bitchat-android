package com.yet.bitmessage.feature.chats.conversations.settings

import com.app.common.decompose.asValue
import com.app.domain.model.ThemeMode
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MeshSettingsRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.PowRepository
import com.app.domain.repository.SettingsRepository
import com.app.domain.repository.ThemeRepository
import com.app.domain.repository.TorRepository
import com.app.domain.usecase.PanicWipeUseCase
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
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
    private val dialogNav = SlotNavigation<SettingsDialog>()

    override val dialog: Value<ChildSlot<*, SettingsDialog>> =
        childSlot(
            source = dialogNav,
            serializer = null,
            handleBackButton = true,
            childFactory = { config, _ -> config },
        )

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
            autoStartEnabled = state.autoStartEnabled,
            backgroundEnabled = state.backgroundEnabled,
        )
    }

    override fun onNicknameChanged(text: String) = store.accept(SettingsStore.Intent.NicknameChanged(text))

    override fun onThemeSelected(mode: ThemeMode) = store.accept(SettingsStore.Intent.ThemeSelected(mode))

    override fun onTorToggled(enabled: Boolean) = store.accept(SettingsStore.Intent.TorToggled(enabled))

    override fun onPowToggled(enabled: Boolean) = store.accept(SettingsStore.Intent.PowToggled(enabled))

    override fun onPowDifficultySelected(difficulty: Int) =
        store.accept(SettingsStore.Intent.PowDifficultySelected(difficulty))

    override fun onAutoStartToggled(enabled: Boolean) = store.accept(SettingsStore.Intent.AutoStartToggled(enabled))

    override fun onBackgroundToggled(enabled: Boolean) = store.accept(SettingsStore.Intent.BackgroundToggled(enabled))

    override fun onPanicWipeClicked() = dialogNav.activate(SettingsDialog.PanicConfirm)

    override fun onConfirmPanicWipe() {
        store.accept(SettingsStore.Intent.PanicWipe)
        dialogNav.dismiss()
    }

    override fun onDismissDialog() = dialogNav.dismiss()

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
    private val meshSettingsRepository: MeshSettingsRepository,
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
                meshSettingsRepository = meshSettingsRepository,
                panicWipe = PanicWipeUseCase(messageRepository, contactRepository, identityRepository),
            ),
            onClose = onClose,
        )
}
