package com.yet.bitmessage.feature.root

import com.app.domain.model.ConversationId
import com.app.domain.model.ThemeMode
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackHandlerOwner
import com.yet.bitmessage.feature.chats.main.ChatsComponent

/**
 * Top-level navigation host. Owns the [ChildStack] of app flows; each flow is a
 * self-contained component (chats today; settings, onboarding, map — later), so
 * adding a screen means adding a `Config` + `Child` pair here and nothing else.
 */
interface RootComponent : BackHandlerOwner {

    val stack: Value<ChildStack<*, Child>>

    /** App-wide theme preference, applied by the host Compose theme. */
    val themeMode: Value<ThemeMode>

    /** Deep-link entry point (notification tap): open a conversation in the chats flow. */
    fun openConversation(id: ConversationId)

    fun onBackClicked()

    sealed interface Child {
        class Chats(val component: ChatsComponent) : Child
    }

    fun interface Factory {
        fun create(componentContext: ComponentContext): RootComponent
    }
}
