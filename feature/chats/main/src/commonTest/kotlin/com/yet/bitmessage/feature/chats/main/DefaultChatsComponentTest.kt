package com.yet.bitmessage.feature.chats.main

import com.app.domain.model.Attachment
import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.app.domain.model.PeerId
import com.app.domain.model.Reachability
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.arkivanov.decompose.router.slot.ChildSlot
import com.yet.bitmessage.feature.chats.conversations.connectivity.ConnectivityComponent
import com.yet.bitmessage.feature.chats.conversations.contacts.ContactsComponent
import com.yet.bitmessage.feature.chats.conversations.boards.BoardDialog
import com.yet.bitmessage.feature.chats.conversations.boards.BoardsComponent
import com.yet.bitmessage.feature.chats.conversations.groups.GroupsComponent
import com.yet.bitmessage.feature.chats.conversations.voice.VoiceComponent
import com.arkivanov.decompose.router.stack.ChildStack
import kotlinx.coroutines.flow.emptyFlow
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
        val onGroupsRequested: () -> Unit,
        val onVoiceRequested: () -> Unit,
        val onBoardsRequested: () -> Unit,
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
        override fun onGroupsClicked() = onGroupsRequested()
        override fun onVoiceClicked() = onVoiceRequested()
        override fun onBoardsClicked() = onBoardsRequested()
        override fun onTogglePin(id: ConversationId) = Unit
        override fun onToggleMute(id: ConversationId) = Unit
        override fun onDismissBanner() = Unit
    }

    private class FakeConnectivityComponent : ConnectivityComponent {
        override val model: Value<ConnectivityComponent.Model> =
            MutableValue(ConnectivityComponent.Model(statuses = emptyList(), peers = emptyList()))

        override fun onEnableClicked(kind: com.app.domain.model.TransportKind) = Unit
        override fun onToggleFavorite(peerIdRaw: String) = Unit
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
                    bookmarks = emptyList(),
                    nearby = emptyList(),
                    recent = emptyList(),
                ),
            )

        override fun onQueryChanged(text: String) = Unit
        override fun onTabSelected(tab: SearchTab) = Unit
        override fun onResultClicked(id: ConversationId) = onResultSelected(id, null)
        override fun onMessageClicked(id: ConversationId, messageId: String) = onResultSelected(id, messageId)
        override fun onPickOnMapClicked() = Unit
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
                    gatewayEnabled = false,
                    myQr = null,
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
        override fun onGatewayToggled(enabled: Boolean) = Unit
        override fun onEnableNotificationsClicked() = Unit
        override fun onShowMyQrClicked() = Unit
        override fun onPanicWipeClicked() = Unit
        override fun onConfirmPanicWipe() = Unit
        override fun onDismissDialog() = Unit
        override fun onOpenDebugClicked() = Unit
        override fun onCloseClicked() = onClose()
    }

    private class FakeGroupsComponent(
        componentContext: ComponentContext,
        val onClose: () -> Unit,
    ) : GroupsComponent, ComponentContext by componentContext {
        override val stack: Value<ChildStack<*, GroupsComponent.Child>>
            get() = error("not used in these tests")

        override fun onBackClicked() = Unit
    }

    private class FakeVoiceComponent(val onClose: () -> Unit) : VoiceComponent {
        override val model: Value<VoiceComponent.Model> = MutableValue(VoiceComponent.Model(received = emptyList()))
        override val playback = emptyFlow<List<ByteArray>>()
        override fun onBurstCaptured(frames: List<ByteArray>, durationMs: Int) = Unit
        override suspend fun requestMicrophonePermission(): Boolean = true
        override fun onCloseClicked() = onClose()
    }

    private class FakeBoardsComponent(val onClose: () -> Unit) : BoardsComponent {
        override val model: Value<BoardsComponent.Model> =
            MutableValue(BoardsComponent.Model(isLoading = false, geohash = "", posts = emptyList()))
        override val dialog: Value<ChildSlot<*, BoardDialog>> = MutableValue(ChildSlot<Any, BoardDialog>())
        override fun onSelectBoard(geohash: String) = Unit
        override fun onCreateClicked() = Unit
        override fun onSubmitCreate(content: String, urgent: Boolean, expiryDays: Int) = Unit
        override fun onDelete(postIdHex: String) = Unit
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
                    participantCount = 0,
                    isBookmarked = false,
                    participants = emptyList(),
                    mentionSuggestions = emptyList(),
                    targetMessageId = null,
                ),
            )

        override val sheetSlot: Value<ChildSlot<*, ChatComponent.ChatSheetChild>> =
            MutableValue(ChildSlot<Any, ChatComponent.ChatSheetChild>())

        override fun onDraftChanged(text: String) = Unit
        override fun onSendClicked() = Unit
        override fun onAttachmentPicked(attachment: Attachment) = Unit
        override fun onCancelTransfer(messageId: String) = Unit
        override fun onMentionSelected(nickname: String) = Unit
        override fun onToggleBookmark() = Unit
        override fun onVerifyClicked() = Unit
        override fun onParticipantsClicked() = Unit
        override fun onNotesClicked() = Unit
        override fun onParticipantSelected(pubkeyHex: String) = Unit
        override fun onDismissSheet() = Unit
        override suspend fun requestMicrophonePermission(): Boolean = true
        override fun onBackClicked() = onFinished()
    }

    private fun build(): DefaultChatsComponent {
        val lifecycle = LifecycleRegistry()
        return DefaultChatsComponent(
            componentContext = DefaultComponentContext(lifecycle),
            conversationsFactory = { _, onSelected, onConnectivity, onSearch, onContacts, onSettings, onGroups, onVoice, onBoards ->
                FakeConversationsComponent(onSelected, onConnectivity, onSearch, onContacts, onSettings, onGroups, onVoice, onBoards)
            },
            chatFactory = { _: ComponentContext, config: ChatConfig, onFinished: () -> Unit, _ ->
                FakeChatComponent(config, onFinished)
            },
            connectivityFactory = { _: ComponentContext -> FakeConnectivityComponent() },
            searchFactory = { _, onResultSelected, _, onClose ->
                FakeSearchComponent(onResultSelected, onClose)
            },
            contactsFactory = { _, onContactSelected, onClose ->
                FakeContactsComponent(onContactSelected, onClose)
            },
            settingsFactory = { _, onClose, _ -> FakeSettingsComponent(onClose) },
            groupsFactory = { ctx, onClose -> FakeGroupsComponent(ctx, onClose) },
            voiceFactory = { _, onClose -> FakeVoiceComponent(onClose) },
            boardsFactory = { _, onClose -> FakeBoardsComponent(onClose) },
            onOpenMap = {},
            onOpenDebug = {},
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
    fun requesting_groups_opens_the_overlay_and_dismiss_closes_it() {
        val component = build()

        component.mainConversations.onGroupsClicked()
        assertIs<ChatsComponent.SheetChild.Groups>(component.sheetSlot.value.child?.instance)

        component.onDismissSheet()
        assertNull(component.sheetSlot.value.child)
    }

    @Test
    fun requesting_voice_opens_the_overlay_and_dismiss_closes_it() {
        val component = build()

        component.mainConversations.onVoiceClicked()
        assertIs<ChatsComponent.SheetChild.Voice>(component.sheetSlot.value.child?.instance)

        component.onDismissSheet()
        assertNull(component.sheetSlot.value.child)
    }

    @Test
    fun requesting_boards_opens_the_overlay_and_dismiss_closes_it() {
        val component = build()

        component.mainConversations.onBoardsClicked()
        assertIs<ChatsComponent.SheetChild.Boards>(component.sheetSlot.value.child?.instance)

        component.onDismissSheet()
        assertNull(component.sheetSlot.value.child)
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
