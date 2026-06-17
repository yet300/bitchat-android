package com.yet.bitmessage.feature.onboarding.store

import com.arkivanov.mvikotlin.core.store.Store
import com.yet.bitmessage.feature.onboarding.OnboardingStep

internal interface OnboardingStore :
    Store<OnboardingStore.Intent, OnboardingStore.State, OnboardingStore.Label> {

    data class State(
        val step: OnboardingStep = OnboardingStep.WELCOME,
        val nickname: String = "",
    )

    sealed interface Intent {
        data class NicknameChanged(val text: String) : Intent

        /** Advance from a non-terminal step (Welcome/Nickname, and the primer steps in A2a). */
        data object Primary : Intent

        /** "Not now" on an optional primer step — advances without granting. */
        data object Skip : Intent

        data object Back : Intent

        /** Terminal step: persist completion, then emit [Label.Finished]. */
        data object Finish : Intent
    }

    sealed interface Action {
        data object Load : Action
    }

    sealed interface Msg {
        data class StepChanged(val step: OnboardingStep) : Msg
        data class NicknameLoaded(val nickname: String) : Msg
    }

    sealed interface Label {
        /** Completion flag is persisted; the host may now navigate to the chats flow. */
        data object Finished : Label
    }
}
