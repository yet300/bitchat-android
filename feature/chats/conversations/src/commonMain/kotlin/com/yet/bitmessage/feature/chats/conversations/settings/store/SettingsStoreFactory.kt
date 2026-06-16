package com.yet.bitmessage.feature.chats.conversations.settings.store

import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MeshSettingsRepository
import com.app.domain.repository.NotificationPermissionRepository
import com.app.domain.repository.NotificationSettingsRepository
import com.app.domain.repository.PowRepository
import com.app.domain.repository.SettingsRepository
import com.app.domain.repository.ThemeRepository
import com.app.domain.repository.TorRepository
import com.app.domain.usecase.PanicWipeUseCase
import com.yet.bitmessage.feature.chats.conversations.settings.NotifPermissionStatus
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch

internal class SettingsStoreFactory(
    private val storeFactory: StoreFactory,
    private val settingsRepository: SettingsRepository,
    private val identityRepository: IdentityRepository,
    private val themeRepository: ThemeRepository,
    private val torRepository: TorRepository,
    private val powRepository: PowRepository,
    private val meshSettingsRepository: MeshSettingsRepository,
    private val notificationSettingsRepository: NotificationSettingsRepository,
    private val notificationPermissionRepository: NotificationPermissionRepository,
    private val panicWipe: PanicWipeUseCase,
) {
    fun create(): SettingsStore =
        object : SettingsStore,
            Store<SettingsStore.Intent, SettingsStore.State, SettingsStore.Label> by storeFactory.create(
                name = "SettingsStore",
                initialState = SettingsStore.State(powLevels = powRepository.difficultyLevels()),
                bootstrapper = SimpleBootstrapper(SettingsStore.Action.Load),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<SettingsStore.State, SettingsStore.Msg> {
        override fun SettingsStore.State.reduce(msg: SettingsStore.Msg): SettingsStore.State =
            when (msg) {
                is SettingsStore.Msg.NicknameLoaded -> copy(nickname = msg.nickname)
                is SettingsStore.Msg.IdentityLoaded -> copy(npub = msg.npub, fingerprint = msg.fingerprint)
                is SettingsStore.Msg.Wiping -> copy(isWiping = msg.wiping)
                is SettingsStore.Msg.ThemeLoaded -> copy(theme = msg.mode)
                is SettingsStore.Msg.TorLoaded -> copy(torEnabled = msg.enabled)
                is SettingsStore.Msg.PowEnabledLoaded -> copy(powEnabled = msg.enabled)
                is SettingsStore.Msg.PowDifficultyLoaded -> copy(powDifficulty = msg.difficulty)
                is SettingsStore.Msg.AutoStartLoaded -> copy(autoStartEnabled = msg.enabled)
                is SettingsStore.Msg.BackgroundLoaded -> copy(backgroundEnabled = msg.enabled)
                is SettingsStore.Msg.NotifPermissionLoaded -> copy(notifPermission = msg.status)
                is SettingsStore.Msg.GlobalMuteLoaded -> copy(globalMuteEnabled = msg.enabled)
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<SettingsStore.Intent, SettingsStore.Action, SettingsStore.State, SettingsStore.Msg, SettingsStore.Label>() {

        override fun executeAction(action: SettingsStore.Action) {
            when (action) {
                SettingsStore.Action.Load -> {
                    scope.launch {
                        settingsRepository.observeNickname().collect { dispatch(SettingsStore.Msg.NicknameLoaded(it)) }
                    }
                    scope.launch { loadIdentity() }
                    scope.launch {
                        themeRepository.observeTheme().collect { dispatch(SettingsStore.Msg.ThemeLoaded(it)) }
                    }
                    scope.launch {
                        torRepository.observeTorEnabled().collect { dispatch(SettingsStore.Msg.TorLoaded(it)) }
                    }
                    scope.launch {
                        powRepository.observePowEnabled().collect { dispatch(SettingsStore.Msg.PowEnabledLoaded(it)) }
                    }
                    scope.launch {
                        powRepository.observePowDifficulty().collect { dispatch(SettingsStore.Msg.PowDifficultyLoaded(it)) }
                    }
                    scope.launch {
                        meshSettingsRepository.observeAutoStart().collect { dispatch(SettingsStore.Msg.AutoStartLoaded(it)) }
                    }
                    scope.launch {
                        meshSettingsRepository.observeBackgroundEnabled().collect { dispatch(SettingsStore.Msg.BackgroundLoaded(it)) }
                    }
                    scope.launch {
                        notificationPermissionRepository.observeGranted().collect { granted ->
                            val status = if (granted) NotifPermissionStatus.GRANTED else NotifPermissionStatus.DENIED
                            dispatch(SettingsStore.Msg.NotifPermissionLoaded(status))
                        }
                    }
                    scope.launch {
                        notificationSettingsRepository.observeGlobalMuteEnabled()
                            .collect { dispatch(SettingsStore.Msg.GlobalMuteLoaded(it)) }
                    }
                }
            }
        }

        override fun executeIntent(intent: SettingsStore.Intent) {
            when (intent) {
                is SettingsStore.Intent.NicknameChanged -> scope.launch {
                    settingsRepository.setNickname(intent.text)
                }
                SettingsStore.Intent.PanicWipe -> scope.launch {
                    dispatch(SettingsStore.Msg.Wiping(true))
                    panicWipe()
                    loadIdentity()
                    dispatch(SettingsStore.Msg.Wiping(false))
                }
                is SettingsStore.Intent.ThemeSelected -> scope.launch { themeRepository.setTheme(intent.mode) }
                is SettingsStore.Intent.TorToggled -> scope.launch { torRepository.setTorEnabled(intent.enabled) }
                is SettingsStore.Intent.PowToggled -> scope.launch { powRepository.setPowEnabled(intent.enabled) }
                is SettingsStore.Intent.PowDifficultySelected -> scope.launch {
                    powRepository.setPowDifficulty(intent.difficulty)
                }
                is SettingsStore.Intent.AutoStartToggled -> scope.launch {
                    meshSettingsRepository.setAutoStart(intent.enabled)
                }
                is SettingsStore.Intent.BackgroundToggled -> scope.launch {
                    meshSettingsRepository.setBackgroundEnabled(intent.enabled)
                }
                is SettingsStore.Intent.GlobalMuteToggled -> scope.launch {
                    notificationSettingsRepository.setGlobalMuteEnabled(intent.enabled)
                }
                SettingsStore.Intent.EnableNotificationsClicked -> scope.launch {
                    notificationPermissionRepository.requestPermission()
                }
            }
        }

        private suspend fun loadIdentity() {
            val identity = identityRepository.myIdentity()
            dispatch(SettingsStore.Msg.IdentityLoaded(identity.nostrNpub, identity.fingerprint.value))
        }
    }
}
