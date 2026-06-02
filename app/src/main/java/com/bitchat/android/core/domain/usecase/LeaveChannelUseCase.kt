package com.bitchat.android.core.domain.usecase

import com.bitchat.android.core.domain.model.Channel
import com.bitchat.android.core.domain.repository.ChannelRepository

/** Leave a channel. */
class LeaveChannelUseCase(
    private val channels: ChannelRepository,
) {
    suspend operator fun invoke(tag: String) = channels.leave(Channel.tag(tag))
}
