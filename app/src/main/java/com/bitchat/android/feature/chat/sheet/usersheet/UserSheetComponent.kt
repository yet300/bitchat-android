package com.bitchat.android.feature.chat.sheet.usersheet

import com.arkivanov.decompose.value.Value
import com.bitchat.android.model.BitchatMessage

interface UserSheetComponent {
    val model: Value<Model>

    fun onSlap()
    fun onHug()
    fun onBlock()
    fun onDismiss()

    data class Model(
        val targetNickname: String,
        val selectedMessage: BitchatMessage?,
        val isSelf: Boolean,
        val isGeohashChannel: Boolean
    )
}
