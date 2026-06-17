package com.yet.bitmessage.feature.onboarding.store

import com.app.domain.repository.OnboardingRepository
import com.app.domain.repository.SettingsRepository
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
                // A2a: the primer steps just advance; A3 wires the permission request before advancing.
                OnboardingStore.Intent.Primary, OnboardingStore.Intent.Skip -> advance()
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
