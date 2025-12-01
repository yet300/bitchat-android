package com.bitchat.android.feature.chat.sheet.about.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.bitchat.android.core.domain.repository.AppInfoRepository
import com.bitchat.android.net.TorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.ui.theme.ThemePreferenceManager
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class AboutStoreFactory(
    private val storeFactory: StoreFactory
) : KoinComponent {

    private val appInfoRepository: AppInfoRepository by inject()

    private val poWPreferenceManager: PoWPreferenceManager by inject()
    private val torManager: TorManager by inject()
    private val torPreferenceManager: TorPreferenceManager by inject()
    private val themePreferenceManager : ThemePreferenceManager by inject()


    fun create(): AboutStore =
        object : AboutStore,
            Store<AboutStore.Intent, AboutStore.State, AboutStore.Label> by storeFactory.create(
                name = "AboutStore",
                initialState = AboutStore.State(
                    versionName = appInfoRepository.getVersionName(),
                    themePreference = themePreferenceManager.themeFlow.value,
                    powEnabled = poWPreferenceManager.powEnabled.value,
                    powDifficulty = poWPreferenceManager.powDifficulty.value,
                    torStatus = torManager.statusFlow.value
                ),
                bootstrapper = SimpleBootstrapper(AboutStore.Action.Init),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl
            ) {}

    private inner class ExecutorImpl :
        CoroutineExecutor<AboutStore.Intent, AboutStore.Action, AboutStore.State, AboutStore.Msg, AboutStore.Label>() {

        override fun executeAction(action: AboutStore.Action) {
            when (action) {
                AboutStore.Action.Init -> {
                    scope.launch {
                        themePreferenceManager.themeFlow.collect {
                            dispatch(AboutStore.Msg.ThemeChanged(it))
                        }
                    }
                    scope.launch {
                        poWPreferenceManager.powEnabled.collect {
                            dispatch(AboutStore.Msg.PowEnabledChanged(it))
                        }
                    }
                    scope.launch {
                        poWPreferenceManager.powDifficulty.collect {
                            dispatch(AboutStore.Msg.PowDifficultyChanged(it))
                        }
                    }
                    scope.launch {
                        torManager.statusFlow.collect {
                            dispatch(AboutStore.Msg.TorStatusChanged(it))
                        }
                    }
                }
            }
        }

        override fun executeIntent(intent: AboutStore.Intent) {
            when (intent) {
                is AboutStore.Intent.SetTheme -> {
                    themePreferenceManager.set(intent.theme)
                }
                is AboutStore.Intent.SetPowEnabled -> {
                    poWPreferenceManager.setPowEnabled(intent.enabled)
                }
                is AboutStore.Intent.SetPowDifficulty -> {
                    poWPreferenceManager.setPowDifficulty(intent.difficulty)
                }
                is AboutStore.Intent.SetTorMode -> {
                    val mode = if (intent.enabled) TorMode.ON else TorMode.OFF
                    torPreferenceManager.set(mode)
                }
            }
        }
    }

    private object ReducerImpl : Reducer<AboutStore.State, AboutStore.Msg> {
        override fun AboutStore.State.reduce(msg: AboutStore.Msg): AboutStore.State =
            when (msg) {
                is AboutStore.Msg.ThemeChanged -> copy(themePreference = msg.theme)
                is AboutStore.Msg.PowEnabledChanged -> copy(powEnabled = msg.enabled)
                is AboutStore.Msg.PowDifficultyChanged -> copy(powDifficulty = msg.difficulty)
                is AboutStore.Msg.TorStatusChanged -> copy(torStatus = msg.status)
            }
    }
}
