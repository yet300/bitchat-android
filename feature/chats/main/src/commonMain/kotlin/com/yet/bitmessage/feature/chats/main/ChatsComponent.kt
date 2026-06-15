package com.yet.bitmessage.feature.chats.main

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.panels.ChildPanels
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackHandlerOwner
import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.yet.bitmessage.feature.chats.conversations.connectivity.ConnectivityComponent
import com.yet.bitmessage.feature.chats.details.ChatComponent

/**
 * Chats flow host: a [ChildPanels] master-detail of the conversation list
 * (main panel) and the active conversation (details panel). The UI drives
 * [setMode] from window size (SINGLE on phones, DUAL on tablets/desktop).
 */
interface ChatsComponent : BackHandlerOwner {

    val panels: Value<ChildPanels<*, Main, *, Details, Nothing, Nothing>>

    /** Optional modal bottom sheet overlaying the chats flow (connectivity, and future sheets). */
    val sheetSlot: Value<ChildSlot<*, SheetChild>>

    fun setMode(mode: ChildPanelsMode)

    fun onBackClicked()

    /** Dismiss the active bottom sheet (scrim tap / back). */
    fun onDismissSheet()

    sealed interface Main {
        class Conversations(val component: ConversationsComponent) : Main
    }

    sealed interface Details {
        class Chat(val component: ChatComponent) : Details
    }

    /** Bottom-sheet children — add new sheet kinds here as the app grows. */
    sealed interface SheetChild {
        class Connectivity(val component: ConnectivityComponent) : SheetChild
    }

    fun interface Factory {
        fun create(componentContext: ComponentContext): ChatsComponent
    }
}
