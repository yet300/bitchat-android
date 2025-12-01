package com.bitchat.android.feature.chat.sheet.passwordprompt.integration

import com.bitchat.android.feature.chat.sheet.passwordprompt.PasswordPromptComponent
import com.bitchat.android.feature.chat.sheet.passwordprompt.store.PasswordPromptStore

internal val stateToModel: (PasswordPromptStore.State) -> PasswordPromptComponent.Model = { state ->
    PasswordPromptComponent.Model(
        channelName = state.channelName,
        passwordInput = state.passwordInput,
        isError = state.isError
    )
}
