package com.bitchat.android.feature.chat.sheet.passwordprompt

import com.arkivanov.decompose.value.Value

interface PasswordPromptComponent {
    val model: Value<Model>

    fun onPasswordChange(password: String)
    fun onConfirm()
    fun onDismiss()

    data class Model(
        val channelName: String,
        val passwordInput: String,
        val isError: Boolean
    )
}
