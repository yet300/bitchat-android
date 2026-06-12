package com.yet.bitmessage.feature.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.router.panels.ChildPanels
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.yet.bitmessage.feature.chats.main.ChatsComponent
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class DefaultRootComponentTest {

    private class FakeChatsComponent(componentContext: ComponentContext) :
        ChatsComponent, ComponentContext by componentContext {
        override val panels: Value<ChildPanels<*, ChatsComponent.Main, *, ChatsComponent.Details, Nothing, Nothing>>
            get() = error("not used in root tests")

        override fun setMode(mode: ChildPanelsMode) = Unit
        override fun onBackClicked() = Unit
    }

    @Test
    fun initial_stack_shows_chats_flow() {
        var created: ChatsComponent? = null
        val component = DefaultRootComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            chatsFactory = { ctx -> FakeChatsComponent(ctx).also { created = it } },
        )

        val child = assertIs<RootComponent.Child.Chats>(component.stack.value.active.instance)
        assertSame(created, child.component)
    }
}
