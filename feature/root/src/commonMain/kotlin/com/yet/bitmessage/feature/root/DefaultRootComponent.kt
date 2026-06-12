package com.yet.bitmessage.feature.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.value.Value
import com.yet.bitmessage.feature.chats.main.ChatsComponent
import dev.zacsweers.metro.Inject
import kotlinx.serialization.Serializable

internal class DefaultRootComponent(
    componentContext: ComponentContext,
    private val chatsFactory: ChatsComponent.Factory,
) : RootComponent, ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    override val stack: Value<ChildStack<*, RootComponent.Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.Chats,
        handleBackButton = true,
        childFactory = ::createChild,
    )

    private fun createChild(config: Config, componentContext: ComponentContext): RootComponent.Child =
        when (config) {
            is Config.Chats -> RootComponent.Child.Chats(chatsFactory.create(componentContext))
        }

    override fun onBackClicked() = navigation.pop()

    @Serializable
    private sealed interface Config {
        @Serializable
        data object Chats : Config
    }
}

@Inject
internal class DefaultRootComponentFactory(
    private val chatsFactory: ChatsComponent.Factory,
) : RootComponent.Factory {
    override fun create(componentContext: ComponentContext): RootComponent =
        DefaultRootComponent(
            componentContext = componentContext,
            chatsFactory = chatsFactory,
        )
}
