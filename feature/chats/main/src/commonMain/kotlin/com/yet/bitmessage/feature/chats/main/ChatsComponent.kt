package com.yet.bitmessage.feature.chats.main

import com.app.domain.model.ConversationId
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.panels.ChildPanels
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackHandlerOwner
import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.yet.bitmessage.feature.chats.conversations.channels.ChannelsComponent
import com.yet.bitmessage.feature.chats.conversations.connectivity.ConnectivityComponent
import com.yet.bitmessage.feature.chats.conversations.contacts.ContactsComponent
import com.yet.bitmessage.feature.chats.conversations.search.SearchComponent
import com.yet.bitmessage.feature.chats.conversations.settings.SettingsComponent
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

    /** Open (or switch to) a conversation in the details panel — used by notification deep-links. */
    fun openConversation(id: ConversationId)

    fun onBackClicked()

    /** Dismiss the active bottom sheet (scrim tap / back). */
    fun onDismissSheet()

    sealed interface Main {
        class Conversations(val component: ConversationsComponent) : Main
    }

    sealed interface Details {
        class Chat(val component: ChatComponent) : Details
    }

    /** Overlay children — modal sheets and the full-screen search / contacts. */
    sealed interface SheetChild {
        class Connectivity(val component: ConnectivityComponent) : SheetChild
        class Search(val component: SearchComponent) : SheetChild
        class Contacts(val component: ContactsComponent) : SheetChild
        class Settings(val component: SettingsComponent) : SheetChild
        class Channels(val component: ChannelsComponent) : SheetChild
    }

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            onOpenMap: (initialGeohash: String?) -> Unit,
        ): ChatsComponent
    }
}
