package com.app.data.channel

/**
 * Narrow channel-password crypto port used by [com.app.data.repository.ChannelRepositoryImpl].
 *
 * Decouples the repository from the heavy Android [com.app.crypto.EncryptionService] (so the
 * repository's password-verification logic is unit-testable without Tink/Context) while keeping the
 * PBKDF2/AES-GCM key derivation in the surviving crypto primitive.
 */
interface ChannelCipher {

    /** Derive and cache the AES key for [channel] from [password]. */
    fun setPassword(password: String, channel: String)

    /** SHA-256 commitment of the cached channel key (null if none) — for verifying a join password. */
    fun keyCommitment(channel: String): String?

    /** Drop the cached key for [channel] (wrong password / leaving). */
    fun removePassword(channel: String)
}
