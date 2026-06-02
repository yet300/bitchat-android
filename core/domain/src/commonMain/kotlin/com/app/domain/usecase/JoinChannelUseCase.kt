package com.app.domain.usecase

import com.app.domain.model.Channel
import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.JoinResult

/** Join/create a channel. The name is normalized to `#name`. */
class JoinChannelUseCase(
    private val channels: ChannelRepository,
) {
    suspend operator fun invoke(tag: String, password: String? = null): JoinResult =
        channels.join(Channel.tag(tag), password)
}
