package com.yet.bitmessage.feature.root

import com.app.domain.model.ConversationId
import com.app.domain.model.ThemeMode
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackHandlerOwner
import com.yet.bitmessage.feature.chats.main.ChatsComponent
import com.yet.bitmessage.feature.map.MapComponent
import com.yet.bitmessage.feature.onboarding.OnboardingComponent

/**
 * Top-level navigation host. Owns the [ChildStack] of app flows; each flow is a
 * self-contained component (onboarding on first run, then chats), so adding a screen
 * means adding a `Config` + `Child` pair here and nothing else.
 */
interface RootComponent : BackHandlerOwner {

    val stack: Value<ChildStack<*, Child>>

    /** App-wide theme preference, applied by the host Compose theme. */
    val themeMode: Value<ThemeMode>

    /** Deep-link entry point (notification tap): open a conversation in the chats flow. */
    fun openConversation(id: ConversationId)

    fun onBackClicked()

    sealed interface Child {
        class Onboarding(val component: OnboardingComponent) : Child
        class Chats(val component: ChatsComponent) : Child
        class Map(val component: MapComponent) : Child
    }

    fun interface Factory {
        fun create(componentContext: ComponentContext): RootComponent
    }
}
