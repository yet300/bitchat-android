package com.yet.bitmessage.feature.chats.main

import com.app.domain.model.Conversation
import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.yet.bitmessage.feature.chats.details.ChatComponent
import com.yet.bitmessage.feature.chats.details.ChatConfig
import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class DefaultChatsComponentTest {

    private class FakeConversationsComponent(
        val onConversationSelected: (ConversationId) -> Unit,
    ) : ConversationsComponent {
        override val model: Value<ConversationsComponent.Model> =
            MutableValue(
                ConversationsComponent.Model(
                    isLoading = false,
                    conversations = emptyList(),
                    nearby = emptyList(),
                    query = "",
                ),
            )

        override fun onConversationClicked(id: ConversationId) = onConversationSelected(id)
        override fun onNearbyClicked(peer: Peer) = onConversationSelected(ConversationId.Private(peer.id))
        override fun onQueryChanged(text: String) = Unit
    }

    private class FakeChatComponent(
        config: ChatConfig,
        val onFinished: () -> Unit,
    ) : ChatComponent {
        override val model: Value<ChatComponent.Model> =
            MutableValue(
                ChatComponent.Model(
                    conversationId = config.toConversationId(),
                    title = "test",
                    isLoading = false,
                    messages = emptyList(),
                    draft = "",
                    canSend = false,
                ),
            )

        override fun onDraftChanged(text: String) = Unit
        override fun onSendClicked() = Unit
        override fun onBackClicked() = onFinished()
    }

    private fun build(): DefaultChatsComponent {
        val lifecycle = LifecycleRegistry()
        return DefaultChatsComponent(
            componentContext = DefaultComponentContext(lifecycle),
            conversationsFactory = { ctx: ComponentContext, onSelected: (ConversationId) -> Unit ->
                FakeConversationsComponent(onSelected)
            },
            chatFactory = { _: ComponentContext, config: ChatConfig, onFinished: () -> Unit ->
                FakeChatComponent(config, onFinished)
            },
        )
    }

    private val DefaultChatsComponent.mainConversations: ConversationsComponent
        get() = (panels.value.main.instance as ChatsComponent.Main.Conversations).component

    @Test
    fun initial_panels_show_conversations_without_details() {
        val component = build()
        assertIs<ChatsComponent.Main.Conversations>(component.panels.value.main.instance)
        assertNull(component.panels.value.details)
    }

    @Test
    fun selecting_a_conversation_opens_chat_details_with_matching_id() {
        val component = build()
        val id = ConversationId.Channel("kotlin")

        component.mainConversations.onConversationClicked(id)

        val details = component.panels.value.details?.instance
        assertNotNull(details)
        val chat = assertIs<ChatsComponent.Details.Chat>(details)
        assertEquals(id, chat.component.model.value.conversationId)
    }

    @Test
    fun chat_onBack_closes_details() {
        val component = build()
        component.mainConversations.onConversationClicked(ConversationId.PublicMesh)
        val chat = assertIs<ChatsComponent.Details.Chat>(component.panels.value.details?.instance)

        chat.component.onBackClicked()

        assertNull(component.panels.value.details)
    }

    @Test
    fun root_onBackClicked_closes_details() {
        val component = build()
        component.mainConversations.onConversationClicked(ConversationId.PublicMesh)

        component.onBackClicked()

        assertNull(component.panels.value.details)
    }

    @Test
    fun setMode_is_reflected_in_panels_state() {
        val component = build()
        component.setMode(ChildPanelsMode.DUAL)
        assertEquals(ChildPanelsMode.DUAL, component.panels.value.mode)
    }
}
