package com.bitchat.android.feature.root

import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.bitchat.android.feature.onboarding.OnboardingComponent
import com.bitchat.android.feature.chat.ChatComponent

interface RootComponent {
    val childStack: Value<ChildStack<*, Child>>

    sealed class Child {
        class Onboarding(val component: OnboardingComponent) : Child()
        class Chat(val component: ChatComponent) : Child()
    }
}
