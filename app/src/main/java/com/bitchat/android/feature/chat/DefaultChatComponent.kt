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

    override val sheetSlot: Value<ChildSlot<*, ChatComponent.SheetChild>> =
        childSlot(
            source = sheetNavigation,
            serializer = SheetConfig.serializer(),
            key = "ChatSheet",
            handleBackButton = true,
            childFactory = ::createSheetChild
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
}
