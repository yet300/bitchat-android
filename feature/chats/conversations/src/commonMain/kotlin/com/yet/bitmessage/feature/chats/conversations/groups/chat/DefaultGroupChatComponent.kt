package com.yet.bitmessage.feature.chats.conversations.groups.chat

import com.app.common.decompose.asValue
import com.app.domain.repository.GroupRepository
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yet.bitmessage.feature.chats.conversations.groups.chat.store.GroupChatStore
import com.yet.bitmessage.feature.chats.conversations.groups.chat.store.GroupChatStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultGroupChatComponent(
    componentContext: ComponentContext,
    storeFactory: GroupChatStoreFactory,
    private val title: String,
    private val onBack: () -> Unit,
) : GroupChatComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }

    override val model: Value<GroupChatComponent.Model> = store.asValue().map { state ->
        GroupChatComponent.Model(
            title = title,
            messages = state.messages.map { message ->
                GroupChatComponent.Message(
                    id = message.id,
                    senderNickname = message.senderNickname,
                    content = message.content,
                    timestampMs = message.timestampMs,
                    isMine = message.isMine,
                )
            },
        )
    }

    override fun onSend(content: String) {
        val trimmed = content.trim()
        if (trimmed.isNotEmpty()) store.accept(GroupChatStore.Intent.Send(trimmed))
    }

    override fun onBackClicked() = onBack()
}

@Inject
internal class DefaultGroupChatComponentFactory(
    private val storeFactory: StoreFactory,
    private val groupRepository: GroupRepository,
) : GroupChatComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        groupIdHex: String,
        title: String,
        onBack: () -> Unit,
    ): GroupChatComponent = DefaultGroupChatComponent(
        componentContext = componentContext,
        storeFactory = GroupChatStoreFactory(storeFactory, groupRepository, groupIdHex),
        title = title,
        onBack = onBack,
    )
}
