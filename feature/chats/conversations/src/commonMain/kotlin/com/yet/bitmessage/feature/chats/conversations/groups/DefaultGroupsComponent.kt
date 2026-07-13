package com.yet.bitmessage.feature.chats.conversations.groups

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.value.Value
import com.yet.bitmessage.feature.chats.conversations.groups.chat.GroupChatComponent
import com.yet.bitmessage.feature.chats.conversations.groups.list.GroupListComponent
import dev.zacsweers.metro.Inject
import kotlinx.serialization.Serializable

internal class DefaultGroupsComponent(
    componentContext: ComponentContext,
    private val listFactory: GroupListComponent.Factory,
    private val chatFactory: GroupChatComponent.Factory,
    private val onClose: () -> Unit,
) : GroupsComponent, ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    override val stack: Value<ChildStack<*, GroupsComponent.Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.List,
        handleBackButton = true,
        childFactory = ::createChild,
    )

    private fun createChild(config: Config, ctx: ComponentContext): GroupsComponent.Child =
        when (config) {
            Config.List -> GroupsComponent.Child.List(
                listFactory.create(
                    componentContext = ctx,
                    onGroupSelected = { id, name -> navigation.push(Config.Chat(id, name)) },
                    onClose = onClose,
                ),
            )
            is Config.Chat -> GroupsComponent.Child.Chat(
                chatFactory.create(
                    componentContext = ctx,
                    groupIdHex = config.groupIdHex,
                    title = config.name,
                    onBack = { navigation.pop() },
                ),
            )
        }

    override fun onBackClicked() = navigation.pop()

    @Serializable
    private sealed interface Config {
        @Serializable
        data object List : Config

        @Serializable
        data class Chat(val groupIdHex: String, val name: String) : Config
    }
}

@Inject
internal class DefaultGroupsComponentFactory(
    private val listFactory: GroupListComponent.Factory,
    private val chatFactory: GroupChatComponent.Factory,
) : GroupsComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        onClose: () -> Unit,
    ): GroupsComponent = DefaultGroupsComponent(
        componentContext = componentContext,
        listFactory = listFactory,
        chatFactory = chatFactory,
        onClose = onClose,
    )
}
