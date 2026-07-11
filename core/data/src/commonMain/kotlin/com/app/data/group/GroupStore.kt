@file:OptIn(ExperimentalTime::class)

package com.app.data.group

import com.app.database.Private_group
import com.app.database.dao.GroupDao
import com.app.transport.model.BitchatGroup
import com.app.transport.model.GroupMember
import com.app.transport.model.GroupRosterCoding
import dev.whyoleg.cryptography.random.CryptographyRandom
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Persistence for private groups: metadata (roster, name, epoch) plus the current-epoch symmetric
 * key, all in the SQLCipher-encrypted DB via [GroupDao]. Platform-free (commonMain), mirroring the
 * reference iOS `GroupStore` — except the key lives in the encrypted DB rather than a keychain, so
 * the panic wipe drops it with everything else (DB crypto-erase; a separate wipe is only for tests).
 */
@SingleIn(AppScope::class)
@Inject
class GroupStore(
    private val groupDao: GroupDao,
) {

    // MARK: - Reads

    suspend fun group(groupID: ByteArray): BitchatGroup? =
        groupDao.selectById(groupID)?.toGroup()

    suspend fun groups(): List<BitchatGroup> =
        groupDao.selectAll().mapNotNull { it.toGroup() }

    /** Current-epoch symmetric key for the group. */
    suspend fun key(groupID: ByteArray): ByteArray? =
        groupDao.selectById(groupID)?.group_key

    // MARK: - Mutations

    /**
     * Creates a new group with a random 16-byte ID and 32-byte key at epoch 1, with [creator] as the
     * sole member. Returns null when persistence rejects it.
     */
    suspend fun createGroup(name: String, creator: GroupMember): BitchatGroup? {
        val group = BitchatGroup(
            groupID = randomBytes(BitchatGroup.GROUP_ID_LENGTH),
            name = name,
            epoch = 1u,
            members = listOf(creator),
            creatorFingerprint = creator.fingerprint,
        )
        return if (upsert(group, randomBytes(BitchatGroup.KEY_LENGTH))) group else null
    }

    /**
     * Inserts or replaces a group and its current key. Rejects an oversize/empty roster, a wrong-size
     * ID or key, or a group whose creator is missing from the roster.
     */
    suspend fun upsert(group: BitchatGroup, key: ByteArray): Boolean {
        if (group.groupID.size != BitchatGroup.GROUP_ID_LENGTH) return false
        if (key.size != BitchatGroup.KEY_LENGTH) return false
        if (group.members.isEmpty() || group.members.size > BitchatGroup.MAX_MEMBERS) return false
        if (group.creator == null) return false
        val roster = GroupRosterCoding.encode(group.members) ?: return false
        groupDao.upsert(
            groupId = group.groupID,
            name = group.name,
            epoch = group.epoch.toLong(),
            creatorFingerprint = group.creatorFingerprint,
            roster = roster,
            groupKey = key,
            nowMs = Clock.System.now().toEpochMilliseconds(),
        )
        return true
    }

    /**
     * Rotates the group key (creator-side invite/removal): fresh random key, `epoch + 1`, and the
     * given roster. Returns the updated group and new key, or null when the group is unknown or
     * persistence rejects it.
     */
    suspend fun rotateKey(groupID: ByteArray, members: List<GroupMember>): Pair<BitchatGroup, ByteArray>? {
        val existing = group(groupID) ?: return null
        val newKey = randomBytes(BitchatGroup.KEY_LENGTH)
        val rotated = BitchatGroup(
            groupID = existing.groupID,
            name = existing.name,
            epoch = existing.epoch + 1u,
            members = members,
            creatorFingerprint = existing.creatorFingerprint,
        )
        return if (upsert(rotated, newKey)) rotated to newKey else null
    }

    suspend fun removeGroup(groupID: ByteArray) = groupDao.deleteById(groupID)

    /** Panic wipe (test-only path; the panic flow crypto-erases the whole DB). */
    suspend fun wipe() = groupDao.wipe()

    private fun Private_group.toGroup(): BitchatGroup? {
        val members = GroupRosterCoding.decode(roster) ?: return null
        return BitchatGroup(
            groupID = group_id,
            name = name,
            epoch = epoch.toUInt(),
            members = members,
            creatorFingerprint = creator_fingerprint,
        )
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { CryptographyRandom.Default.nextBytes(it) }
}
