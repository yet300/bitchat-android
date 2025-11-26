package com.bitchat.android.feature.chat

import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.Value

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
    
    sealed interface SheetChild {
        data object AppInfo : SheetChild
        data object LocationChannels : SheetChild
        data object LocationNotes : SheetChild
        data class UserSheet(val nickname: String, val messageId: String?) : SheetChild
    }
    
    sealed interface DialogChild {
        data class PasswordPrompt(val channelName: String) : DialogChild
    }
}
