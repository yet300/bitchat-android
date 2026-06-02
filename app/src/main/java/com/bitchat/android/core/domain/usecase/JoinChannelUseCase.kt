package com.bitchat.android.core.domain.usecase

import com.bitchat.android.core.domain.model.Channel
import com.bitchat.android.core.domain.repository.ChannelRepository
import com.bitchat.android.core.domain.repository.JoinResult

/** Join/create a channel. The name is normalized to `#name`. */
class JoinChannelUseCase(
    private val channels: ChannelRepository,
) {
    suspend operator fun invoke(tag: String, password: String? = null): JoinResult =
        channels.join(Channel.tag(tag), password)
}
