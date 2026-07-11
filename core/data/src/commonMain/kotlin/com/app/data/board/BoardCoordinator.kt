@file:OptIn(ExperimentalTime::class)

package com.app.data.board

import com.app.common.AppDispatchers
import com.app.common.encoding.hexEncodedString
import com.app.crypto.EncryptionService
import com.app.database.Board_entry
import com.app.database.dao.BoardDao
import com.app.database.dao.BoardIngestResult
import com.app.domain.model.BoardPost
import com.app.domain.repository.BoardRepository
import com.app.domain.repository.SettingsRepository
import com.app.transport.board.BoardEventListener
import com.app.transport.mesh.MeshService
import com.app.transport.model.BoardPostPacket
import com.app.transport.model.BoardTombstonePacket
import com.app.transport.model.BoardWire
import com.app.transport.model.BoardWireConstants
import dev.whyoleg.cryptography.random.CryptographyRandom
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

/**
 * Owns the geohash bulletin board (0x23), ported from the reference iOS `BoardManager` + `BoardStore`.
 * Platform-free (commonMain), mirroring [com.app.data.group.GroupCoordinator]: attaches itself as
 * [MeshService.boardEventListener] and builds/signs posts + tombstones, while [BoardDao] owns the
 * persistence and all anti-spam bounds (per-author + global caps, orphan caps, retention) — see
 * docs/GROUPS_BOARDS_RESEARCH.md §3.3.
 *
 * Signatures are the sole authenticity gate (boards accept from any node): the mesh verifies the
 * inbound inner signature before [onBoardPacketReceived], and outbound posts are signed here.
 */
@SingleIn(AppScope::class)
@Inject
class BoardCoordinator(
    private val meshService: MeshService,
    private val encryption: EncryptionService,
    private val boardDao: BoardDao,
    private val settings: SettingsRepository,
    dispatchers: AppDispatchers,
) : BoardEventListener, BoardRepository {

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())

    private val _arrivals = MutableSharedFlow<BoardPost>(extraBufferCapacity = 64)
    override val postArrivals: SharedFlow<BoardPost> = _arrivals

    init {
        meshService.boardEventListener = this
    }

    // MARK: - BoardRepository

    override suspend fun posts(geohash: String): List<BoardPost> {
        val mine = mySigningKey()
        return boardDao.livePostsForGeohash(geohash, nowMs())
            .map { it.toBoardPost(mine) }
            .sortedWith(compareByDescending<BoardPost> { it.isUrgent }.thenByDescending { it.createdAt })
    }

    override suspend fun createPost(content: String, geohash: String, urgent: Boolean, expiryDays: Int): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed.encodeToByteArray().size > BoardWireConstants.CONTENT_MAX_BYTES) return false
        val signingKey = mySigningKey() ?: return false
        if (signingKey.size != BoardWireConstants.SIGNING_KEY_LENGTH) return false

        val nickname = truncatedNickname(currentNickname())
        val createdAt = nowMs().toULong()
        val lifetime = minOf(maxOf(1, expiryDays).toLong() * DAY_MS, BoardWireConstants.MAX_LIFETIME_MS)
        val expiresAt = createdAt + lifetime.toULong()
        val flags: UByte = if (urgent) BoardWireConstants.URGENT_FLAG.toUByte() else 0u
        val postID = ByteArray(BoardWireConstants.POST_ID_LENGTH).also { CryptographyRandom.Default.nextBytes(it) }

        val signingBytes = BoardPostPacket.signingBytes(postID, geohash, trimmed, signingKey, nickname, createdAt, expiresAt, flags)
        val signature = encryption.signData(signingBytes) ?: return false
        val packet = BoardPostPacket(postID, geohash, trimmed, signingKey, nickname, createdAt, expiresAt, flags, signature)

        ingestPost(packet)
        meshService.sendBoardPayload(BoardWire.Post(packet).encode())
        return true
    }

    override suspend fun deletePost(postIdHex: String): Boolean {
        val postID = hexToBytes(postIdHex) ?: return false
        val signingKey = mySigningKey() ?: return false
        // Only our own posts: the post must be known locally and authored by us.
        val known = boardDao.syncCandidates(nowMs())
            .firstOrNull { it.kind == 1L && it.post_id.contentEquals(postID) } ?: return false
        if (!known.author_key.contentEquals(signingKey)) return false

        val deletedAt = nowMs().toULong()
        val signingBytes = BoardTombstonePacket.signingBytes(postID, deletedAt)
        val signature = encryption.signData(signingBytes) ?: return false
        val tombstone = BoardTombstonePacket(postID, signingKey, deletedAt, signature)

        boardDao.ingestTombstone(postID, signingKey, deletedAt.toLong(), signature, nowMs())
        meshService.sendBoardPayload(BoardWire.Tombstone(tombstone).encode())
        return true
    }

    // MARK: - BoardEventListener (inbound; already signature-verified by the mesh)

    override fun onBoardPacketReceived(payload: ByteArray) {
        scope.launch {
            when (val wire = BoardWire.decode(payload)) {
                is BoardWire.Post -> ingestPost(wire.post)
                is BoardWire.Tombstone -> boardDao.ingestTombstone(
                    wire.tombstone.postID, wire.tombstone.authorSigningKey,
                    wire.tombstone.deletedAt.toLong(), wire.tombstone.signature, nowMs(),
                )
                null -> Unit
            }
        }
    }

    // MARK: - Internals

    private suspend fun ingestPost(post: BoardPostPacket) {
        val result = boardDao.ingestPost(
            postId = post.postID, authorKey = post.authorSigningKey, geohash = post.geohash,
            content = post.content, nickname = post.authorNickname, createdAt = post.createdAt.toLong(),
            expiresAt = post.expiresAt.toLong(), flags = post.flags.toLong(), signature = post.signature,
            nowMs = nowMs(),
        )
        if (result == BoardIngestResult.ACCEPTED) {
            val mine = mySigningKey()
            _arrivals.emit(
                BoardPost(
                    idHex = post.postID.hexEncodedString(), geohash = post.geohash, content = post.content,
                    authorKeyHex = post.authorSigningKey.hexEncodedString(), authorNickname = post.authorNickname,
                    createdAt = post.createdAt.toLong(), expiresAt = post.expiresAt.toLong(),
                    isUrgent = post.isUrgent, isMine = mine != null && post.authorSigningKey.contentEquals(mine),
                )
            )
        }
    }

    private fun Board_entry.toBoardPost(mine: ByteArray?): BoardPost = BoardPost(
        idHex = post_id.hexEncodedString(), geohash = geohash, content = content,
        authorKeyHex = author_key.hexEncodedString(), authorNickname = nickname,
        createdAt = created_at, expiresAt = expires_at,
        isUrgent = (flags.toInt() and BoardWireConstants.URGENT_FLAG) != 0,
        isMine = mine != null && author_key.contentEquals(mine),
    )

    private fun mySigningKey(): ByteArray? = encryption.getSigningPublicKey()

    private suspend fun currentNickname(): String = try {
        settings.observeNickname().first()
    } catch (_: Exception) {
        ""
    }

    private fun truncatedNickname(nickname: String): String {
        var candidate = nickname
        while (candidate.encodeToByteArray().size > BoardWireConstants.NICKNAME_MAX_BYTES) {
            candidate = candidate.dropLast(1)
        }
        return candidate
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
