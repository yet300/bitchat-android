package com.app.domain.usecase

import com.app.domain.repository.MessageTransport
import com.app.domain.repository.SettingsRepository

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
