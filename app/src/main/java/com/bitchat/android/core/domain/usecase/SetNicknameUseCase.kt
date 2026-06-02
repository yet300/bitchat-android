package com.bitchat.android.core.domain.usecase

import com.bitchat.android.core.domain.repository.MessageTransport
import com.bitchat.android.core.domain.repository.SettingsRepository

/** Change the nickname and announce self to the network. An empty nickname is ignored. */
class SetNicknameUseCase(
    private val settings: SettingsRepository,
    private val transport: MessageTransport,
) {
    suspend operator fun invoke(nickname: String) {
        val trimmed = nickname.trim()
        if (trimmed.isEmpty()) return
        settings.setNickname(trimmed)
        transport.announceSelf()
    }
}
