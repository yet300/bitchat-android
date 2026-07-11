package com.app.transport.group

/**
 * Private-group events, surfaced as a narrow SPI so the platform-free group coordinator can own the
 * membership/crypto policy and the group store without implementing the full
 * [BluetoothMeshDelegate][com.app.transport.mesh.BluetoothMeshDelegate]. Mirrors
 * [CourierEventListener][com.app.transport.courier.CourierEventListener] /
 * [VouchEventListener][com.app.transport.vouch.VouchEventListener].
 *
 * Two inbound surfaces:
 * - a `MessageType.GROUP_MESSAGE (0x25)` broadcast: opaque ciphertext here — the coordinator opens it,
 *   authenticates the sender against the creator-signed roster, and delivers it. Non-members relay
 *   the packet (generic broadcast relay) but never decode.
 * - creator-signed group state carried 1:1 over a Noise session as `GROUP_INVITE (0x06)` /
 *   `GROUP_KEY_UPDATE (0x07)`.
 */
interface GroupEventListener {

    /** An inbound 0x25 group broadcast (opaque `GroupMessageEnvelope` payload + packet timestamp). */
    fun onGroupMessageReceived(payload: ByteArray, timestampMs: Long)

    /**
     * Creator-signed group state from [fromPeerID] over its authenticated Noise session; [isInvite]
     * distinguishes 0x06 (invite) from 0x07 (key rotation / roster update).
     */
    fun onGroupStateReceived(fromPeerID: String, isInvite: Boolean, payload: ByteArray)
}
