package com.yet.bitmessage.feature.onboarding

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackHandlerOwner

/**
 * First-run onboarding flow: a linear, permissionless sequence that sells the value prop and
 * collects a nickname before landing in the chats flow. No step blocks on a permission grant — the
 * nearby/notification steps are optional primers. Completion is persisted by the store; the host
 * navigates away via [Factory.create]'s `onFinished` callback once the flag is written.
 */
interface OnboardingComponent : BackHandlerOwner {

    val model: Value<Model>

    data class Model(
        val step: OnboardingStep,
        val nickname: String,
    )

    fun onNicknameChanged(text: String)

    /** Primary action for the current step (continue / enable / finish). */
    fun onPrimaryClicked()

    /** Secondary "not now" action on the optional primer steps — advances without granting. */
    fun onSkipClicked()

    fun onBackClicked()

    fun interface Factory {
        fun create(componentContext: ComponentContext, onFinished: () -> Unit): OnboardingComponent
    }
}

/** Ordered onboarding steps. Forward order = [WELCOME] → [DONE]; the store advances/regresses by ordinal. */
enum class OnboardingStep {
    WELCOME,
    NICKNAME,
    NEARBY,
    NOTIFICATIONS,
    DONE,
}
