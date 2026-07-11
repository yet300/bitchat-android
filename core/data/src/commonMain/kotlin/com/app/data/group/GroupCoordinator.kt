@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.app.data.group

import com.app.common.AppDispatchers
import com.app.common.encoding.hexEncodedString
import com.app.crypto.EncryptionService
import com.app.domain.model.GroupInfo
import com.app.domain.model.GroupMessageEvent
import com.app.domain.repository.GroupRepository
import com.app.domain.repository.SettingsRepository
import com.app.transport.group.GroupEventListener
import com.app.transport.mesh.MeshService
import com.app.transport.model.BitchatGroup
import com.app.transport.model.GroupCrypto
import com.app.transport.model.GroupMember
import com.app.transport.model.GroupMessageEnvelope
import com.app.transport.model.GroupStatePayload
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Owns the private-groups feature (0x25 broadcasts + 0x06/0x07 state), ported from the reference iOS
 * `ChatGroupCoordinator`. Platform-free (commonMain), mirroring [com.app.data.vouch.VouchCoordinator]:
 * attaches itself as [MeshService.groupEventListener] and drives creator-only membership + rotation,
 * message sealing/opening, and roster-authenticated delivery, while [GroupStore] owns persistence and
 * [GroupCrypto] owns the wire crypto.
 *
 * v1 limitations (parity with the reference): a single creator authority; state reaches only
 * *connected* members (offline members catch up next time the creator sends them state); leaving is
 * local-only.
 */
@SingleIn(AppScope::class)
@Inject
class GroupCoordinator(
    private val meshService: MeshService,
    private val encryption: EncryptionService,
    private val groupStore: GroupStore,
    private val settings: SettingsRepository,
    dispatchers: AppDispatchers,
) : GroupEventListener, GroupRepository {

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())

    private val _incoming = MutableSharedFlow<GroupMessageEvent>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<GroupMessageEvent> = _incoming

    private val sign: (ByteArray) -> ByteArray? = { data -> encryption.signData(data) }
    private val verify: (ByteArray, ByteArray, ByteArray) -> Boolean =
        { publicKey, data, signature -> encryption.verifyEd25519Signature(signature, data, publicKey) }

    init {
        meshService.groupEventListener = this
    }

    // MARK: - GroupRepository (operations)

    override suspend fun listGroups(): List<GroupInfo> {
        val myFp = myFingerprint()
        return groupStore.groups().map { group ->
            GroupInfo(
                idHex = group.groupID.hexEncodedString(),
                name = group.name,
                epoch = group.epoch.toInt(),
                memberCount = group.members.size,
                isCreator = myFp != null && group.creatorFingerprint.contentEquals(myFp),
            )
        }
    }

    override suspend fun createGroup(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) return null
        val fingerprint = myFingerprint() ?: return null
        val signingKey = mySigningKey() ?: return null
        val creator = GroupMember(fingerprint, signingKey, currentNickname())
        return groupStore.createGroup(trimmed, creator)?.groupID?.hexEncodedString()
    }

    override suspend fun invite(groupIdHex: String, peerId: String): Boolean {
        val groupID = hexToBytes(groupIdHex) ?: return false
        val group = groupStore.group(groupID) ?: return false
        if (!isCreator(group)) return false
        if (group.members.size >= BitchatGroup.MAX_MEMBERS) return false
        val (fingerprint, signingKey) = cryptoIdentity(peerId) ?: return false
        if (group.isMember(fingerprint)) return false

        val newMember = GroupMember(fingerprint, signingKey, peerNickname(peerId))
        // Rotate the key (epoch+1) on every roster change: a monotonic epoch per roster gives receivers
        // a strict ordering, so two out-of-order invite states can't last-writer-wins a member back out.
        val members = group.members + newMember
        val (rotated, key) = groupStore.rotateKey(groupID, members) ?: return false
        val payload = signedState(rotated, key) ?: return false

        meshService.sendGroupState(payload, peerId, isInvite = true)
        distributeState(payload, rotated, excluding = setOf(fingerprint.hexEncodedString()))
        return true
    }

    override suspend fun removeMember(groupIdHex: String, memberFingerprintHex: String): Boolean {
        val groupID = hexToBytes(groupIdHex) ?: return false
        val group = groupStore.group(groupID) ?: return false
        if (!isCreator(group)) return false
        val target = hexToBytes(memberFingerprintHex) ?: return false
        val member = group.members.firstOrNull { it.fingerprint.contentEquals(target) } ?: return false
        if (member.fingerprint.contentEquals(group.creatorFingerprint)) return false

        val remaining = group.members.filterNot { it.fingerprint.contentEquals(target) }
        val (rotated, newKey) = groupStore.rotateKey(groupID, remaining) ?: return false
        val payload = signedState(rotated, newKey) ?: return false

        distributeState(payload, rotated, excluding = emptySet())
        notifyRemovedMember(member, rotated)
        return true
    }

    override suspend fun leave(groupIdHex: String) {
        val groupID = hexToBytes(groupIdHex) ?: return
        groupStore.removeGroup(groupID)
    }

    override suspend fun sendMessage(groupIdHex: String, content: String): Boolean {
        if (content.isEmpty()) return false
        val groupID = hexToBytes(groupIdHex) ?: return false
        val group = groupStore.group(groupID) ?: return false
        val key = groupStore.key(groupID) ?: return false
        val signingKey = mySigningKey() ?: return false


        val payload = GroupCrypto.sealMessage(
            content = content,
            messageID = Uuid.random().toString().uppercase(),
            senderNickname = currentNickname(),
            senderSigningKey = signingKey,
            timestampMs = nowMs().toULong(),
            groupID = groupID,
            epoch = group.epoch,
            key = key,
            sign = sign,
        ) ?: return false

        meshService.broadcastGroupMessage(payload)
        return true
    }

    // MARK: - GroupEventListener (inbound)

    override fun onGroupMessageReceived(payload: ByteArray, timestampMs: Long) {
        scope.launch { handleInboundMessage(payload) }
    }

    override fun onGroupStateReceived(fromPeerID: String, isInvite: Boolean, payload: ByteArray) {
        scope.launch { handleInboundState(fromPeerID, payload) }
    }

    private suspend fun handleInboundMessage(payload: ByteArray) {
        val envelope = GroupMessageEnvelope.decode(payload) ?: return
        val group = groupStore.group(envelope.groupID) ?: return // non-member: relayed but never read
        if (envelope.epoch != group.epoch) return // drop non-current epoch (post-rotation, stale)
        val key = groupStore.key(envelope.groupID) ?: return
        val plaintext = GroupCrypto.openMessage(envelope, key, verify) ?: return

        // Sender must be pinned in the creator-signed roster; key possession alone is not authorship.
        val member = group.member(plaintext.senderSigningKey) ?: return
        // Drop our own broadcast echoed back via relay/sync.
        val mySigning = mySigningKey()
        if (mySigning != null && plaintext.senderSigningKey.contentEquals(mySigning)) return

        // Trust the authenticated inner timestamp, clamped so a future-dated message can't jump ahead.
        val clamped = minOf(plaintext.timestampMs.toLong(), nowMs())
        _incoming.emit(
            GroupMessageEvent(
                groupIdHex = envelope.groupID.hexEncodedString(),
                messageId = plaintext.messageID,
                senderFingerprintHex = member.fingerprintHex,
                senderNickname = member.nickname.ifEmpty { plaintext.senderNickname },
                content = plaintext.content,
                timestampMs = clamped,
            )
        )
    }

    private suspend fun handleInboundState(fromPeerID: String, payload: ByteArray) {
        val state = GroupStatePayload.decode(payload) ?: return
        // The Noise session already authenticated `fromPeerID`; require that peer to BE the creator
        // whose key signed the state, so a member can't re-invite or rotate on the creator's behalf.
        val senderFingerprint = meshFingerprint(fromPeerID) ?: return
        if (!senderFingerprint.contentEquals(state.creatorFingerprint)) return
        if (!state.verifyCreatorSignature(verify)) return

        val myFp = myFingerprint() ?: return
        val existing = groupStore.group(state.groupID)

        // A creator-signed roster that no longer includes us is a removal.
        if (state.members.none { it.fingerprint.contentEquals(myFp) }) {
            if (existing != null) groupStore.removeGroup(existing.groupID)
            return
        }
        // Never regress the epoch: state travels over live Noise, so an older epoch is a stale device.
        if (existing != null && state.epoch < existing.epoch) return

        groupStore.upsert(state.asGroup(), state.key)
    }

    // MARK: - State distribution

    /** Sends [payload] to every connected roster member except us and the [excluding] fingerprints. */
    private fun distributeState(payload: ByteArray, group: BitchatGroup, excluding: Set<String>) {
        val myFpHex = myFingerprint()?.hexEncodedString()
        for (member in group.members) {
            val fpHex = member.fingerprintHex
            if (fpHex == myFpHex || fpHex in excluding) continue
            val peerID = connectedPeerID(member.fingerprint) ?: continue
            meshService.sendGroupState(payload, peerID, isInvite = false)
        }
    }

    /**
     * Tells a just-removed member they're out so their client deactivates the group instead of going
     * silently dark. The notice is creator-signed state whose roster excludes them, carrying a
     * throwaway all-zero key (never the rotated key), sent 1:1 over Noise — so they can't decrypt
     * post-removal traffic, and no remaining member ever sees this blob.
     */
    private fun notifyRemovedMember(removed: GroupMember, rotated: BitchatGroup) {
        val peerID = connectedPeerID(removed.fingerprint) ?: return
        val throwawayKey = ByteArray(BitchatGroup.KEY_LENGTH)
        val payload = signedState(rotated, throwawayKey) ?: return
        meshService.sendGroupState(payload, peerID, isInvite = false)
    }

    private fun signedState(group: BitchatGroup, key: ByteArray): ByteArray? =
        GroupStatePayload.makeSigned(group, key, sign)?.encode()

    // MARK: - Identity / peer helpers

    private fun isCreator(group: BitchatGroup): Boolean {
        val myFp = myFingerprint() ?: return false
        return group.creatorFingerprint.contentEquals(myFp)
    }

    private fun myFingerprint(): ByteArray? = hexToBytes(encryption.getIdentityFingerprint())

    private fun mySigningKey(): ByteArray? = encryption.getSigningPublicKey()

    private suspend fun currentNickname(): String = try {
        settings.observeNickname().first()
    } catch (_: Exception) {
        ""
    }

    /** The peer's Noise fingerprint (raw 32 bytes) from the live session/registry. */
    private fun meshFingerprint(peerID: String): ByteArray? =
        meshService.getPeerFingerprint(peerID)?.let { hexToBytes(it) }

    /** The peer's (fingerprint, Ed25519 signing key) from its signature-verified announce. */
    private fun cryptoIdentity(peerID: String): Pair<ByteArray, ByteArray>? {
        val signingKey = meshService.getPeerInfo(peerID)?.signingPublicKey ?: return null
        val fingerprint = meshFingerprint(peerID) ?: return null
        return fingerprint to signingKey
    }

    private fun peerNickname(peerID: String): String =
        meshService.getPeerNicknames()[peerID] ?: meshService.getPeerInfo(peerID)?.nickname ?: ""

    /** Short mesh peer IDs are a fingerprint's first 16 hex chars; connected if the registry knows it. */
    private fun connectedPeerID(fingerprint: ByteArray): String? {
        val shortID = fingerprint.hexEncodedString().take(16)
        return if (meshService.getPeerInfo(shortID) != null) shortID else null
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
        }
    }

    companion object {
        private const val MAX_NAME_LENGTH = 40
    }
}
