package com.yet.bitmessage.feature.chats.main

import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.Reachability
import com.app.domain.model.RetentionPolicy
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.arkivanov.decompose.router.slot.ChildSlot
import com.yet.bitmessage.feature.chats.conversations.channels.ChannelDialog
import com.yet.bitmessage.feature.chats.conversations.channels.ChannelsComponent
import com.yet.bitmessage.feature.chats.conversations.connectivity.ConnectivityComponent
import com.yet.bitmessage.feature.chats.conversations.contacts.ContactsComponent
import com.yet.bitmessage.feature.chats.conversations.search.SearchComponent
import com.yet.bitmessage.feature.chats.conversations.search.SearchTab
import com.yet.bitmessage.feature.chats.conversations.settings.NotifPermissionStatus
import com.yet.bitmessage.feature.chats.conversations.settings.SettingsComponent
import com.yet.bitmessage.feature.chats.conversations.settings.SettingsDialog
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
        val onContactsRequested: () -> Unit,
        val onSettingsRequested: () -> Unit,
        val onChannelsRequested: () -> Unit,
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
        override fun onContactsClicked() = onContactsRequested()
        override fun onSettingsClicked() = onSettingsRequested()
        override fun onChannelsClicked() = onChannelsRequested()
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
        val onResultSelected: (ConversationId, String?) -> Unit,
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
                    geo = null,
                    nearby = emptyList(),
                    recent = emptyList(),
                ),
            )

        override fun onQueryChanged(text: String) = Unit
        override fun onTabSelected(tab: SearchTab) = Unit
        override fun onResultClicked(id: ConversationId) = onResultSelected(id, null)
        override fun onMessageClicked(id: ConversationId, messageId: String) = onResultSelected(id, messageId)
        override fun onCloseClicked() = onClose()
    }

    private class FakeContactsComponent(
        val onContactSelected: (ConversationId) -> Unit,
        val onClose: () -> Unit,
    ) : ContactsComponent {
        override val model: Value<ContactsComponent.Model> =
            MutableValue(ContactsComponent.Model(isLoading = false, favorites = emptyList(), blocked = emptyList()))

        override fun onContactClicked(noiseKeyHex: String) =
            onContactSelected(ConversationId.Private(com.app.domain.model.PeerId(noiseKeyHex)))
        override fun onToggleBlock(noiseKeyHex: String, blocked: Boolean) = Unit
        override fun onCloseClicked() = onClose()
    }

    private class FakeSettingsComponent(val onClose: () -> Unit) : SettingsComponent {
        override val model: Value<SettingsComponent.Model> =
            MutableValue(
                SettingsComponent.Model(
                    nickname = "",
                    npub = null,
                    fingerprint = "",
                    isWiping = false,
                    theme = com.app.domain.model.ThemeMode.SYSTEM,
                    torEnabled = false,
                    powEnabled = false,
                    powDifficulty = 0,
                    powLevels = emptyList(),
                    autoStartEnabled = true,
                    backgroundEnabled = true,
                    notifPermission = NotifPermissionStatus.GRANTED,
                    globalMuteEnabled = false,
                ),
            )
        override val dialog: Value<ChildSlot<*, SettingsDialog>> = MutableValue(ChildSlot<Any, SettingsDialog>())

        override fun onNicknameChanged(text: String) = Unit
        override fun onThemeSelected(mode: com.app.domain.model.ThemeMode) = Unit
        override fun onTorToggled(enabled: Boolean) = Unit
        override fun onPowToggled(enabled: Boolean) = Unit
        override fun onPowDifficultySelected(difficulty: Int) = Unit
        override fun onAutoStartToggled(enabled: Boolean) = Unit
        override fun onBackgroundToggled(enabled: Boolean) = Unit
        override fun onGlobalMuteToggled(enabled: Boolean) = Unit
        override fun onEnableNotificationsClicked() = Unit
        override fun onPanicWipeClicked() = Unit
        override fun onConfirmPanicWipe() = Unit
        override fun onDismissDialog() = Unit
        override fun onCloseClicked() = onClose()
    }

    private class FakeChannelsComponent(
        val onChannelSelected: (String) -> Unit,
        val onClose: () -> Unit,
    ) : ChannelsComponent {
        override val model: Value<ChannelsComponent.Model> =
            MutableValue(ChannelsComponent.Model(isLoading = false, channels = emptyList(), error = null))
        override val dialog: Value<ChildSlot<*, ChannelDialog>> = MutableValue(ChildSlot<Any, ChannelDialog>())

        override fun onChannelClicked(tag: String) = onChannelSelected(tag)
        override fun onJoin(tag: String) = Unit
        override fun onSetPasswordClicked(tag: String) = Unit
        override fun onRetentionClicked(tag: String) = Unit
        override fun onLeave(tag: String) = Unit
        override fun onSubmitPassword(tag: String, mode: ChannelDialog.Password.Mode, password: String) = Unit
        override fun onRetentionSelected(tag: String, policy: RetentionPolicy) = Unit
        override fun onDismissDialog() = Unit
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
                    isVerified = false,
                    targetMessageId = null,
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
            conversationsFactory = { _, onSelected, onConnectivity, onSearch, onContacts, onSettings, onChannels ->
                FakeConversationsComponent(onSelected, onConnectivity, onSearch, onContacts, onSettings, onChannels)
            },
            chatFactory = { _: ComponentContext, config: ChatConfig, onFinished: () -> Unit ->
                FakeChatComponent(config, onFinished)
            },
            connectivityFactory = { _: ComponentContext -> FakeConnectivityComponent() },
            searchFactory = { _, onResultSelected, onClose ->
                FakeSearchComponent(onResultSelected, onClose)
            },
            contactsFactory = { _, onContactSelected, onClose ->
                FakeContactsComponent(onContactSelected, onClose)
            },
            settingsFactory = { _, onClose -> FakeSettingsComponent(onClose) },
            channelsFactory = { _, onChannelSelected, onClose ->
                FakeChannelsComponent(onChannelSelected, onClose)
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
    fun requesting_contacts_opens_the_overlay_and_dm_navigates_and_closes() {
        val component = build()
        component.mainConversations.onContactsClicked()
        val contacts = assertIs<ChatsComponent.SheetChild.Contacts>(component.sheetSlot.value.child?.instance)

        contacts.component.onContactClicked("a".repeat(64))

        assertNull(component.sheetSlot.value.child)
        assertIs<ChatsComponent.Details.Chat>(component.panels.value.details?.instance)
    }

    @Test
    fun open_conversation_deep_link_shows_chat_details() {
        val component = build()
        val id = ConversationId.Private(PeerId("a".repeat(64)))

        component.openConversation(id)

        val chat = assertIs<ChatsComponent.Details.Chat>(component.panels.value.details?.instance)
        assertEquals(id, chat.component.model.value.conversationId)
    }

    @Test
    fun requesting_settings_opens_the_overlay() {
        val component = build()
        component.mainConversations.onSettingsClicked()
        assertIs<ChatsComponent.SheetChild.Settings>(component.sheetSlot.value.child?.instance)
    }

    @Test
    fun requesting_channels_opens_the_overlay_and_open_navigates_and_closes() {
        val component = build()
        component.mainConversations.onChannelsClicked()
        val channels = assertIs<ChatsComponent.SheetChild.Channels>(component.sheetSlot.value.child?.instance)

        channels.component.onChannelClicked("#kotlin")

        assertNull(component.sheetSlot.value.child)
        val chat = assertIs<ChatsComponent.Details.Chat>(component.panels.value.details?.instance)
        assertEquals(ConversationId.Channel("#kotlin"), chat.component.model.value.conversationId)
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
