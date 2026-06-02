package com.bitchat.android.core.domain.usecase

import com.bitchat.android.core.domain.model.Channel
import com.bitchat.android.core.domain.repository.ChannelRepository
import com.bitchat.android.core.domain.repository.JoinResult

/** Войти/создать канал. Имя нормализуется к `#name`. */
class JoinChannelUseCase(
    private val channels: ChannelRepository,
) {
    suspend operator fun invoke(tag: String, password: String? = null): JoinResult =
        channels.join(Channel.tag(tag), password)
}
