package com.bitchat.android.feature.chat

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.value.Value
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent

class DefaultChatComponent(
    componentContext: ComponentContext
) : ChatComponent, ComponentContext by componentContext, KoinComponent {

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
            is SheetConfig.AppInfo -> ChatComponent.SheetChild.AppInfo
            is SheetConfig.LocationChannels -> ChatComponent.SheetChild.LocationChannels
            is SheetConfig.LocationNotes -> ChatComponent.SheetChild.LocationNotes
            is SheetConfig.UserSheet -> ChatComponent.SheetChild.UserSheet(
                nickname = config.nickname,
                messageId = config.messageId
            )
        }

    private fun createDialogChild(
        config: DialogConfig,
        componentContext: ComponentContext
    ): ChatComponent.DialogChild =
        when (config) {
            is DialogConfig.PasswordPrompt -> ChatComponent.DialogChild.PasswordPrompt(
                channelName = config.channelName
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
