package com.app.domain.usecase

import com.app.domain.model.Channel
import com.app.domain.repository.ChannelRepository

/** Leave a channel. */
class LeaveChannelUseCase(
    private val channels: ChannelRepository,
) {
    suspend operator fun invoke(tag: String) = channels.leave(Channel.tag(tag))
}
