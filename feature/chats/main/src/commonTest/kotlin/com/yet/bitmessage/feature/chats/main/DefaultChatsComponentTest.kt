package com.yet.bitmessage.feature.chats.main

import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.app.domain.model.Reachability
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.yet.bitmessage.feature.chats.conversations.connectivity.ConnectivityComponent
import com.yet.bitmessage.feature.chats.conversations.search.SearchComponent
import com.yet.bitmessage.feature.chats.conversations.search.SearchTab
import com.yet.bitmessage.feature.chats.details.ChatComponent
import com.yet.bitmessage.feature.chats.details.ChatConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class DefaultChatsComponentTest {

    private class FakeConversationsComponent(
        val onConversationSelected: (ConversationId) -> Unit,
        val onConnectivityRequested: () -> Unit,
        val onSearchRequested: () -> Unit,
    ) : ConversationsComponent {
        override val model: Value<ConversationsComponent.Model> =
            MutableValue(
                ConversationsComponent.Model(
                    isLoading = false,
                    conversations = emptyList(),
                    nearby = emptyList(),
                ),
            )

        override fun onConversationClicked(id: ConversationId) = onConversationSelected(id)
        override fun onNearbyClicked(peer: Peer) = onConversationSelected(ConversationId.Private(peer.id))
        override fun onConnectivityClicked() = onConnectivityRequested()
        override fun onSearchClicked() = onSearchRequested()
        override fun onTogglePin(id: ConversationId) = Unit
        override fun onToggleMute(id: ConversationId) = Unit
        override fun onDismissBanner() = Unit
    }

    private class FakeConnectivityComponent : ConnectivityComponent {
        override val model: Value<ConnectivityComponent.Model> =
            MutableValue(ConnectivityComponent.Model(statuses = emptyList()))

        override fun onEnableClicked(kind: com.app.domain.model.TransportKind) = Unit
    }

    private class FakeSearchComponent(
        val onResultSelected: (ConversationId) -> Unit,
        val onClose: () -> Unit,
    ) : SearchComponent {
        override val model: Value<SearchComponent.Model> =
            MutableValue(
                SearchComponent.Model(
                    query = "",
                    tab = SearchTab.CHATS,
                    isActive = false,
                    chats = emptyList(),
                    people = emptyList(),
                    messages = emptyList(),
                    channels = emptyList(),
                ),
            )

        override fun onQueryChanged(text: String) = Unit
        override fun onTabSelected(tab: SearchTab) = Unit
        override fun onResultClicked(id: ConversationId) = onResultSelected(id)
        override fun onCloseClicked() = onClose()
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
                    reachability = Reachability.OFFLINE,
                    isEncrypted = false,
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
            conversationsFactory = { _, onSelected, onConnectivity, onSearch ->
                FakeConversationsComponent(onSelected, onConnectivity, onSearch)
            },
            chatFactory = { _: ComponentContext, config: ChatConfig, onFinished: () -> Unit ->
                FakeChatComponent(config, onFinished)
            },
            connectivityFactory = { _: ComponentContext -> FakeConnectivityComponent() },
            searchFactory = { _, onResultSelected, onClose ->
                FakeSearchComponent(onResultSelected, onClose)
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

    @Test
    fun sheet_slot_is_closed_initially() {
        val component = build()
        assertNull(component.sheetSlot.value.child)
    }

    @Test
    fun requesting_connectivity_opens_the_sheet_and_dismiss_closes_it() {
        val component = build()

        component.mainConversations.onConnectivityClicked()
        assertIs<ChatsComponent.SheetChild.Connectivity>(component.sheetSlot.value.child?.instance)

        component.onDismissSheet()
        assertNull(component.sheetSlot.value.child)
    }

    @Test
    fun requesting_search_opens_the_overlay() {
        val component = build()

        component.mainConversations.onSearchClicked()

        assertIs<ChatsComponent.SheetChild.Search>(component.sheetSlot.value.child?.instance)
    }

    @Test
    fun selecting_a_search_result_opens_chat_details_and_closes_search() {
        val component = build()
        component.mainConversations.onSearchClicked()
        val search = assertIs<ChatsComponent.SheetChild.Search>(component.sheetSlot.value.child?.instance)
        val id = ConversationId.Channel("kotlin")

        search.component.onResultClicked(id)

        assertNull(component.sheetSlot.value.child)
        val chat = assertIs<ChatsComponent.Details.Chat>(component.panels.value.details?.instance)
        assertEquals(id, chat.component.model.value.conversationId)
    }
}
