package com.yet.bitmessage.feature.chats.conversations.groups

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackHandlerOwner
import com.yet.bitmessage.feature.chats.conversations.groups.chat.GroupChatComponent
import com.yet.bitmessage.feature.chats.conversations.groups.list.GroupListComponent

/**
 * Private-groups flow (0x25). A self-contained master-detail [ChildStack]: the group list, and one
 * group's live chat. The parent hosts this as a single sheet; navigation between list and chat stays
 * inside here so the rest of the app only knows "the groups screen".
 */
interface GroupsComponent : BackHandlerOwner {

    val stack: Value<ChildStack<*, Child>>

    fun onBackClicked()

    sealed interface Child {
        class List(val component: GroupListComponent) : Child
        class Chat(val component: GroupChatComponent) : Child
    }

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            onClose: () -> Unit,
        ): GroupsComponent
    }
}
