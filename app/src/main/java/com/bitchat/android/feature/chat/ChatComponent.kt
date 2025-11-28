package com.bitchat.android.feature.chat

import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.Value
import com.bitchat.android.feature.about.AboutComponent
import com.bitchat.android.feature.chat.locationchannels.LocationChannelsComponent
import com.bitchat.android.feature.chat.locationnotes.LocationNotesComponent

interface ChatComponent {
    val sheetSlot: Value<ChildSlot<*, SheetChild>>
    val dialogSlot: Value<ChildSlot<*, DialogChild>>
    
    fun onDismissSheet()
    fun onShowAppInfo()
    fun onShowLocationChannels()
    fun onShowLocationNotes()
    fun onShowUserSheet(nickname: String, messageId: String?)
    
    fun onDismissDialog()
    fun onShowPasswordPrompt(channelName: String)

    sealed interface ChatStartupConfig {
        data object Default : ChatStartupConfig
        data class PrivateChat(val peerId: String) : ChatStartupConfig
        data class GeohashChat(val geohash: String) : ChatStartupConfig
    }
    
    sealed interface SheetChild {
        data class AppInfo(val component: AboutComponent) : SheetChild
        data class LocationChannels(val component: LocationChannelsComponent) : SheetChild
        data class LocationNotes(val component: LocationNotesComponent) : SheetChild
        data class UserSheet(val component: com.bitchat.android.feature.chat.usersheet.UserSheetComponent) : SheetChild
    }
    
    sealed interface DialogChild {
        data class PasswordPrompt(val component: com.bitchat.android.feature.chat.passwordprompt.PasswordPromptComponent) : DialogChild
    }
}
