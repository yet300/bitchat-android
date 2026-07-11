package com.app.database.dao

import com.app.common.AppDispatchers
import com.app.database.TestDatabaseDriverFactory
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BoardDao anti-spam bounds: receive-time sanity, per-author (5) + global (200) post caps with
 * oldest-first eviction, author-only delete, post/tombstone interaction, orphan-tombstone caps,
 * and expiry/retention pruning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardDaoTest {

    private lateinit var manager: DatabaseManager
    private lateinit var dispatchers: AppDispatchers
    private lateinit var dao: BoardDao

    private val now = 1_000_000L
    private val day = 24L * 60 * 60 * 1000
    private fun pid(n: Int) = ByteArray(16) { n.toByte() }
    private fun author(n: Int) = ByteArray(32) { (n + it).toByte() }
    private val sig = ByteArray(64) { 7 }

    @BeforeTest
    fun setUp() {
        dispatchers = AppDispatchers(io = UnconfinedTestDispatcher())
        manager = DatabaseManager(TestDatabaseDriverFactory(), dispatchers)
        dao = BoardDao(manager, dispatchers)
    }

    private suspend fun post(
        id: Int, author: ByteArray, geohash: String = "9q8yy",
        createdAt: Long = now, expiresAt: Long = now + day, at: Long = now,
    ) = dao.ingestPost(pid(id), author, geohash, "c$id", "n", createdAt, expiresAt, 0, sig, at)

    @Test
    fun accepts_and_dedups_a_post() = runTest {
        assertEquals(BoardIngestResult.ACCEPTED, post(1, author(1)))
        assertEquals(BoardIngestResult.DUPLICATE, post(1, author(1)))
        assertEquals(1, dao.livePostsForGeohash("9q8yy", now).size)
    }

    @Test
    fun rejects_expired_future_and_overlong_posts() = runTest {
        assertEquals(BoardIngestResult.REJECTED, post(1, author(1), expiresAt = now - 1))
        // createdAt more than an hour in the future.
        assertEquals(BoardIngestResult.REJECTED, post(2, author(1), createdAt = now + 2 * 60 * 60 * 1000, expiresAt = now + 3 * day))
        // expiresAt beyond 7d + skew.
        assertEquals(BoardIngestResult.REJECTED, post(3, author(1), expiresAt = now + 8 * day))
    }

    @Test
    fun per_author_cap_evicts_oldest_first() = runTest {
        repeat(6) { i -> post(i, author(1), createdAt = now + i) } // 6th over the per-author cap of 5
        val live = dao.livePostsForGeohash("9q8yy", now)
        assertEquals(5, live.size)
        // The oldest (createdAt = now+0, id 0) was evicted.
        assertEquals(false, live.any { it.post_id.contentEquals(pid(0)) })
    }

    @Test
    fun author_only_delete_and_post_hidden_by_tombstone() = runTest {
        post(1, author(1))
        // A different author cannot tombstone author(1)'s post.
        assertEquals(BoardIngestResult.REJECTED, dao.ingestTombstone(pid(1), author(2), now, sig, now))
        assertEquals(1, dao.livePostsForGeohash("9q8yy", now).size)
        // The author can; the post is then hidden and a later re-post is rejected.
        assertEquals(BoardIngestResult.ACCEPTED, dao.ingestTombstone(pid(1), author(1), now, sig, now))
        assertEquals(0, dao.livePostsForGeohash("9q8yy", now).size)
        assertEquals(BoardIngestResult.REJECTED, post(1, author(1)))
    }

    @Test
    fun orphan_tombstone_dedups_and_caps_per_author() = runTest {
        // Tombstones for posts we never saw are orphans; 6th from one author over the cap of 5.
        repeat(6) { i -> dao.ingestTombstone(pid(100 + i), author(1), now, sig, now) }
        val candidates = dao.syncCandidates(now)
        assertEquals(5, candidates.count { it.kind == 2L && it.is_orphan == 1L })
        // Re-ingesting the same tombstone is a duplicate.
        assertEquals(BoardIngestResult.DUPLICATE, dao.ingestTombstone(pid(101), author(1), now, sig, now))
    }

    @Test
    fun expired_posts_and_tombstones_are_pruned() = runTest {
        post(1, author(1), expiresAt = now + 10)
        dao.ingestTombstone(pid(200), author(1), now, sig, now) // orphan, retain ~7d
        // Advance past the post expiry but before the tombstone retention.
        assertEquals(0, dao.livePostsForGeohash("9q8yy", now + 20).size)
        assertEquals(1, dao.syncCandidates(now + 20).count { it.kind == 2L })
    }
}
