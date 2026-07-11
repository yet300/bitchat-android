package com.app.domain.repository

import com.app.domain.model.GroupInfo
import com.app.domain.model.GroupMessageEvent
import kotlinx.coroutines.flow.Flow

/**
 * Private encrypted groups (the reference iOS successor to password channels): a creator-managed
 * roster with a per-epoch symmetric key. Messages are ChaCha20-Poly1305 broadcasts (0x25);
 * membership state (invite/rotation) travels 1:1 over Noise (0x06 / 0x07). Only the creator can
 * invite or remove, and every roster change rotates the key (epoch+1) so a removed member's key
 * stops decrypting future traffic.
 *
 * Group IDs and member fingerprints cross this API as lowercase hex. Headless: this is the
 * business-layer surface; a future UI consumes it.
 */
interface GroupRepository {

    /** Authenticated, decrypted inbound group messages (our own echoes excluded). */
    val incomingMessages: Flow<GroupMessageEvent>

    /** Groups this device currently belongs to. */
    suspend fun listGroups(): List<GroupInfo>

    /** Creates a new group with this device as the sole (creator) member. Returns its hex ID or null. */
    suspend fun createGroup(name: String): String?

    /** Creator-only: adds the connected peer [peerId] to [groupIdHex], rotating the key. */
    suspend fun invite(groupIdHex: String, peerId: String): Boolean

    /** Creator-only: removes the member with [memberFingerprintHex], rotating the key. */
    suspend fun removeMember(groupIdHex: String, memberFingerprintHex: String): Boolean

    /** Leaves (and forgets) a group locally. */
    suspend fun leave(groupIdHex: String)

    /** Seals and broadcasts a message to [groupIdHex]. */
    suspend fun sendMessage(groupIdHex: String, content: String): Boolean
}
