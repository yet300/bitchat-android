package com.app.domain.model

/** Summary of a private group this device belongs to (for the group list). */
data class GroupInfo(
    /** Lowercase-hex of the 16-byte group ID. */
    val idHex: String,
    val name: String,
    /** Current key-rotation epoch. */
    val epoch: Int,
    val memberCount: Int,
    /** Whether this device is the group's creator (the only role that can invite/remove). */
    val isCreator: Boolean,
)

/** An authenticated, decrypted group message delivered from the mesh. */
data class GroupMessageEvent(
    val groupIdHex: String,
    val messageId: String,
    /** Lowercase-hex fingerprint of the roster-pinned sender. */
    val senderFingerprintHex: String,
    val senderNickname: String,
    val content: String,
    val timestampMs: Long,
)
