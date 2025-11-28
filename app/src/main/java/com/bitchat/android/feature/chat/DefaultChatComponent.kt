package com.bitchat.android.feature.chat

import com.bitchat.android.feature.about.DefaultAboutComponent
import com.bitchat.android.feature.chat.locationchannels.DefaultLocationChannelsComponent
import com.bitchat.android.feature.chat.locationnotes.DefaultLocationNotesComponent
import com.bitchat.android.feature.chat.usersheet.DefaultUserSheetComponent
import com.bitchat.android.feature.chat.passwordprompt.DefaultPasswordPromptComponent

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.value.Value
import kotlinx.serialization.Serializable
import com.bitchat.android.ui.ChatViewModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DefaultChatComponent(
    componentContext: ComponentContext,
    startupConfig: ChatComponent.ChatStartupConfig
) : ChatComponent, ComponentContext by componentContext, KoinComponent {

    private val chatViewModel: ChatViewModel by inject()

    init {
        when (startupConfig) {
            is ChatComponent.ChatStartupConfig.PrivateChat -> {
                chatViewModel.startPrivateChat(startupConfig.peerId)
                chatViewModel.clearNotificationsForSender(startupConfig.peerId)
            }
            is ChatComponent.ChatStartupConfig.GeohashChat -> {
                val geohash = startupConfig.geohash
                val level = when (geohash.length) {
                    7 -> com.bitchat.android.geohash.GeohashChannelLevel.BLOCK
                    6 -> com.bitchat.android.geohash.GeohashChannelLevel.NEIGHBORHOOD
                    5 -> com.bitchat.android.geohash.GeohashChannelLevel.CITY
                    4 -> com.bitchat.android.geohash.GeohashChannelLevel.PROVINCE
                    2 -> com.bitchat.android.geohash.GeohashChannelLevel.REGION
                    else -> com.bitchat.android.geohash.GeohashChannelLevel.CITY
                }
                
                val geohashChannel = com.bitchat.android.geohash.GeohashChannel(level, geohash)
                val channelId = com.bitchat.android.geohash.ChannelID.Location(geohashChannel)
                chatViewModel.selectLocationChannel(channelId)
                chatViewModel.setCurrentGeohash(geohash)
                chatViewModel.clearNotificationsForGeohash(geohash)
            }
            ChatComponent.ChatStartupConfig.Default -> {}
        }
    }

    private val sheetNavigation = SlotNavigation<SheetConfig>()
    private val dialogNavigation = SlotNavigation<DialogConfig>()

    override val sheetSlot: Value<ChildSlot<*, ChatComponent.SheetChild>> =
        childSlot(
            source = sheetNavigation,
            serializer = SheetConfig.serializer(),
            key = "ChatSheet",
            handleBackButton = true,
            childFactory = ::createSheetChild
        )

    override val dialogSlot: Value<ChildSlot<*, ChatComponent.DialogChild>> =
        childSlot(
            source = dialogNavigation,
            serializer = DialogConfig.serializer(),
            key = "ChatDialog",
            handleBackButton = true,
            childFactory = ::createDialogChild
        )

    override fun onDismissSheet() {
        sheetNavigation.dismiss()
    }

    override fun onShowAppInfo() {
        sheetNavigation.activate(SheetConfig.AppInfo)
    }

    override fun onShowLocationChannels() {
        sheetNavigation.activate(SheetConfig.LocationChannels)
    }

    override fun onShowLocationNotes() {
        sheetNavigation.activate(SheetConfig.LocationNotes)
    }

    override fun onShowUserSheet(nickname: String, messageId: String?) {
        sheetNavigation.activate(SheetConfig.UserSheet(nickname, messageId))
    }

    override fun onDismissDialog() {
        dialogNavigation.dismiss()
    }

    override fun onShowPasswordPrompt(channelName: String) {
        dialogNavigation.activate(DialogConfig.PasswordPrompt(channelName))
    }

    private fun createSheetChild(
        config: SheetConfig,
        componentContext: ComponentContext
    ): ChatComponent.SheetChild =
        when (config) {
            is SheetConfig.AppInfo -> ChatComponent.SheetChild.AppInfo(
                component = DefaultAboutComponent(
                    componentContext = componentContext,
                    onDismissCallback = ::onDismissSheet
                )
            )
            is SheetConfig.LocationChannels -> ChatComponent.SheetChild.LocationChannels(
                component = DefaultLocationChannelsComponent(
                    componentContext = componentContext,
                    onDismissCallback = ::onDismissSheet
                )
            )
            is SheetConfig.LocationNotes -> ChatComponent.SheetChild.LocationNotes(
                component = DefaultLocationNotesComponent(
                    componentContext = componentContext
                )
            )
            is SheetConfig.UserSheet -> {
                val selectedMessage = config.messageId?.let { messageId ->
                    chatViewModel.messages.value?.find { it.id == messageId }
                }

                ChatComponent.SheetChild.UserSheet(
                    component = DefaultUserSheetComponent(
                        componentContext = componentContext,
                        targetNickname = config.nickname,
                        selectedMessage = selectedMessage,
                        currentNickname = chatViewModel.nickname.value ?: "",
                        isGeohashChannel = chatViewModel.selectedLocationChannel.value is com.bitchat.android.geohash.ChannelID.Location,
                        onDismissCallback = ::onDismissSheet
                    )
                )
            }
        }

    private fun createDialogChild(
        config: DialogConfig,
        componentContext: ComponentContext
    ): ChatComponent.DialogChild =
        when (config) {
            is DialogConfig.PasswordPrompt -> ChatComponent.DialogChild.PasswordPrompt(
                component = DefaultPasswordPromptComponent(
                    componentContext = componentContext,
                    channelName = config.channelName,
                    onDismissCallback = ::onDismissDialog
                )
            )
        }

    @Serializable
    sealed interface SheetConfig {
        @Serializable
        data object AppInfo : SheetConfig

        @Serializable
        data object LocationChannels : SheetConfig

        @Serializable
        data object LocationNotes : SheetConfig

        @Serializable
        data class UserSheet(
            val nickname: String,
            val messageId: String?
        ) : SheetConfig
    }

    @Serializable
    sealed interface DialogConfig {
        @Serializable
        data class PasswordPrompt(val channelName: String) : DialogConfig
    }
}
