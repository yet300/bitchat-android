package com.app.data.channel

import com.app.crypto.EncryptionService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** [ChannelCipher] backed by the recovered [EncryptionService] PBKDF2/AES-GCM channel primitive. */
@SingleIn(AppScope::class)
@Inject
internal class EncryptionServiceChannelCipher(
    private val encryption: EncryptionService,
) : ChannelCipher {
    override fun setPassword(password: String, channel: String) =
        encryption.setChannelPassword(password, channel)

    override fun keyCommitment(channel: String): String? =
        encryption.channelKeyCommitment(channel)

    override fun removePassword(channel: String) =
        encryption.removeChannelPassword(channel)
}
