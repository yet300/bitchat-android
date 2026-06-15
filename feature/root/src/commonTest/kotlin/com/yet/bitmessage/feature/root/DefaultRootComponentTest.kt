package com.yet.bitmessage.feature.root

import com.app.domain.model.ConversationId
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.router.panels.ChildPanels
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.yet.bitmessage.feature.chats.main.ChatsComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class DefaultRootComponentTest {

    private class FakeChatsComponent(componentContext: ComponentContext) :
        ChatsComponent, ComponentContext by componentContext {
        val opened = mutableListOf<ConversationId>()

        override val panels: Value<ChildPanels<*, ChatsComponent.Main, *, ChatsComponent.Details, Nothing, Nothing>>
            get() = error("not used in root tests")
        override val sheetSlot: Value<ChildSlot<*, ChatsComponent.SheetChild>>
            get() = error("not used in root tests")

        override fun setMode(mode: ChildPanelsMode) = Unit
        override fun openConversation(id: ConversationId) { opened += id }
        override fun onBackClicked() = Unit
        override fun onDismissSheet() = Unit
    }

    private fun build(onCreated: (FakeChatsComponent) -> Unit = {}) = DefaultRootComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        chatsFactory = { ctx -> FakeChatsComponent(ctx).also(onCreated) },
    )

    @Test
    fun initial_stack_shows_chats_flow() {
        var created: ChatsComponent? = null
        val component = build { created = it }

        val child = assertIs<RootComponent.Child.Chats>(component.stack.value.active.instance)
        assertSame(created, child.component)
    }

    @Test
    fun open_conversation_forwards_the_deep_link_to_the_chats_flow() {
        var created: FakeChatsComponent? = null
        val component = build { created = it }
        val id: ConversationId = ConversationId.Private(com.app.domain.model.PeerId("a".repeat(64)))

        component.openConversation(id)

        assertEquals(listOf(id), created!!.opened)
    }
}
