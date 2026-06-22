package com.yet.bitmessage.feature.onboarding.store

import com.app.domain.model.TransportKind
import com.app.domain.repository.ConnectivityRepository
import com.app.domain.repository.NotificationPermissionRepository
import com.app.domain.repository.OnboardingRepository
import com.app.domain.repository.SettingsRepository
import com.app.domain.usecase.RequestBatteryExemptionOnceUseCase
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.yet.bitmessage.feature.onboarding.OnboardingStep
import kotlinx.coroutines.launch

internal class OnboardingStoreFactory(
    private val storeFactory: StoreFactory,
    private val settingsRepository: SettingsRepository,
    private val onboardingRepository: OnboardingRepository,
    private val connectivityRepository: ConnectivityRepository,
    private val notificationPermissionRepository: NotificationPermissionRepository,
    private val requestBatteryExemptionOnce: RequestBatteryExemptionOnceUseCase,
) {
    fun create(): OnboardingStore =
        object : OnboardingStore,
            Store<OnboardingStore.Intent, OnboardingStore.State, OnboardingStore.Label> by storeFactory.create(
                name = "OnboardingStore",
                initialState = OnboardingStore.State(),
                bootstrapper = SimpleBootstrapper(OnboardingStore.Action.Load),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<OnboardingStore.State, OnboardingStore.Msg> {
        override fun OnboardingStore.State.reduce(msg: OnboardingStore.Msg): OnboardingStore.State =
            when (msg) {
                is OnboardingStore.Msg.StepChanged -> copy(step = msg.step)
                is OnboardingStore.Msg.NicknameLoaded -> copy(nickname = msg.nickname)
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<OnboardingStore.Intent, OnboardingStore.Action, OnboardingStore.State, OnboardingStore.Msg, OnboardingStore.Label>() {

        override fun executeAction(action: OnboardingStore.Action) {
            when (action) {
                OnboardingStore.Action.Load -> scope.launch {
                    settingsRepository.observeNickname().collect { dispatch(OnboardingStore.Msg.NicknameLoaded(it)) }
                }
            }
        }

        override fun executeIntent(intent: OnboardingStore.Intent) {
            when (intent) {
                is OnboardingStore.Intent.NicknameChanged -> scope.launch {
                    settingsRepository.setNickname(intent.text)
                }
                // On the primer steps the primary action requests the relevant permission (in-app
                // dialog, settings fallback) and then advances — denial never blocks the flow.
                OnboardingStore.Intent.Primary -> scope.launch {
                    when (state().step) {
                        OnboardingStep.NEARBY -> {
                            connectivityRepository.enable(TransportKind.BLUETOOTH)
                            // Optional, one-time: keep the background mesh alive under OEM standby.
                            requestBatteryExemptionOnce()
                        }
                        OnboardingStep.NOTIFICATIONS -> notificationPermissionRepository.requestPermission()
                        else -> Unit
                    }
                    advance()
                }
                OnboardingStore.Intent.Skip -> advance()
                OnboardingStore.Intent.Back -> regress()
                OnboardingStore.Intent.Finish -> scope.launch {
                    onboardingRepository.setCompleted()
                    publish(OnboardingStore.Label.Finished)
                }
            }
        }

        private fun advance() = step(forward = true)

        private fun regress() = step(forward = false)

        private fun step(forward: Boolean) {
            val steps = OnboardingStep.entries
            val current = state().step.ordinal
            val next = (if (forward) current + 1 else current - 1).coerceIn(0, steps.lastIndex)
            if (next != current) dispatch(OnboardingStore.Msg.StepChanged(steps[next]))
        }
    }
}
