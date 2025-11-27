package com.bitchat.android.feature.root

import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.bitchat.android.feature.onboarding.OnboardingComponent
import com.bitchat.android.feature.chat.ChatComponent
import com.bitchat.android.ui.theme.ThemePreference

interface RootComponent {
    val model : Value<Model>

    val childStack: Value<ChildStack<*, Child>>
    
    fun onDeepLink(deepLink: DeepLinkData)

    sealed class Child {
        class Onboarding(val component: OnboardingComponent) : Child()
        class Chat(val component: ChatComponent) : Child()
    }

    data class Model(
        val  theme: ThemePreference
    )
}
