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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CourierDao resource bounds: per-depositor quotas, verified-tier pool cap, oldest-verified-first
 * eviction (a verified deposit never displaces a favorite), idempotency by ciphertext, expiry
 * pruning, destructive tag handover, non-destructive remote handover with cooldown, spray halving.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CourierDaoTest {

    private lateinit var manager: DatabaseManager
    private lateinit var dispatchers: AppDispatchers
    private lateinit var dao: CourierDao

    private val now = 1_000_000L
    private val farExpiry = now + 60L * 60 * 1000
    private fun tag(n: Int) = ByteArray(16) { n.toByte() }
    private fun key(n: Int) = ByteArray(32) { (n + it).toByte() }
    private fun ct(n: Int) = ByteArray(8) { (0xF0 + n + it).toByte() }

    private suspend fun deposit(
        n: Int,
        depositor: ByteArray,
        tier: CourierTier,
        copies: Int = 1,
        tag: ByteArray = tag(n),
        expiry: Long = farExpiry,
        at: Long = now,
    ) = dao.deposit(tag, expiry, ct(n), depositor, at, tier, copies, prekeyId = null, nowMs = at)

    @BeforeTest
    fun setUp() {
        dispatchers = AppDispatchers(io = UnconfinedTestDispatcher())
        manager = DatabaseManager(TestDatabaseDriverFactory(), dispatchers)
        dao = CourierDao(manager, dispatchers)
    }

    @Test
    fun per_favorite_depositor_quota() = runTest {
        val d = key(1)
        repeat(CourierDao.Limits.MAX_PER_FAVORITE_DEPOSITOR) { i ->
            assertTrue(deposit(i, d, CourierTier.FAVORITE))
        }
        assertFalse(deposit(99, d, CourierTier.FAVORITE), "6th from same favorite rejected")
        assertEquals(CourierDao.Limits.MAX_PER_FAVORITE_DEPOSITOR.toLong(), dao.count(now))
    }

    @Test
    fun verified_depositor_quota_is_tighter() = runTest {
        val d = key(2)
        assertTrue(deposit(0, d, CourierTier.VERIFIED))
        assertTrue(deposit(1, d, CourierTier.VERIFIED))
        assertFalse(deposit(2, d, CourierTier.VERIFIED), "3rd from same verified rejected")
    }

    @Test
    fun idempotent_by_ciphertext_keeps_larger_budget() = runTest {
        val d = key(3)
        assertTrue(dao.deposit(tag(0), farExpiry, ct(0), d, now, CourierTier.FAVORITE, 2, null, now))
        // Same ciphertext, larger budget: accepted idempotently, still one row.
        assertTrue(dao.deposit(tag(0), farExpiry, ct(0), d, now, CourierTier.FAVORITE, 6, null, now))
        assertEquals(1L, dao.count(now))
        val row = manager.getDb().courierStoreQueries.selectAll().executeAsList().single()
        assertEquals(6L, row.copies)
    }

    @Test
    fun expired_envelopes_are_pruned() = runTest {
        assertTrue(dao.deposit(tag(0), now + 10, ct(0), key(1), now, CourierTier.FAVORITE, 1, null, now))
        assertEquals(0L, dao.count(now + 20))
    }

    @Test
    fun destructive_take_by_tag_removes_matches() = runTest {
        assertTrue(deposit(0, key(1), CourierTier.FAVORITE, tag = tag(7)))
        assertTrue(deposit(1, key(2), CourierTier.FAVORITE, tag = tag(9)))
        val taken = dao.takeByTags(listOf(tag(7)), now)
        assertEquals(1, taken.size)
        assertEquals(1L, dao.count(now))
    }

    @Test
    fun remote_handover_is_non_destructive_and_honours_cooldown() = runTest {
        assertTrue(deposit(0, key(1), CourierTier.FAVORITE, tag = tag(7)))
        val cooldown = 10 * 60 * 1000L
        val first = dao.remoteHandover(listOf(tag(7)), cooldown, now)
        assertEquals(1, first.size)
        assertEquals(1L, dao.count(now), "kept (speculative)")
        // Within cooldown: skipped.
        assertTrue(dao.remoteHandover(listOf(tag(7)), cooldown, now + 1000).isEmpty())
        // After cooldown: emitted again.
        assertEquals(1, dao.remoteHandover(listOf(tag(7)), cooldown, now + cooldown + 1).size)
    }

    @Test
    fun spray_halves_budget_and_skips_ineligible() = runTest {
        val depositor = key(1)
        val courier = key(2)
        assertTrue(deposit(0, depositor, CourierTier.FAVORITE, copies = 4, tag = tag(7)))
        // carry-only envelope: not sprayable.
        assertTrue(deposit(1, depositor, CourierTier.FAVORITE, copies = 1, tag = tag(8)))
        // envelope addressed to the courier: rides the handover path, not spray.
        val courierTag = tag(5)
        assertTrue(deposit(2, depositor, CourierTier.FAVORITE, copies = 4, tag = courierTag))

        val sprayed = dao.spray(courier, courierTags = listOf(courierTag), nowMs = now)
        assertEquals(1, sprayed.size)
        assertEquals(2, sprayed.single().copies) // 4 / 2
        // Source kept its half.
        val kept = manager.getDb().courierStoreQueries.selectAll().executeAsList().first { it.recipient_tag.contentEquals(tag(7)) }
        assertEquals(2L, kept.copies)
        // Second encounter with the same courier burns no more budget.
        assertTrue(dao.spray(courier, courierTags = listOf(courierTag), nowMs = now).isEmpty())
    }

    @Test
    fun full_store_evicts_oldest_verified_first_and_protects_favorites() = runTest {
        // Fill with favorites from distinct depositors (respecting the per-depositor cap).
        var n = 0
        for (depositorIdx in 0 until 8) {
            repeat(CourierDao.Limits.MAX_PER_FAVORITE_DEPOSITOR) {
                assertTrue(deposit(n, key(100 + depositorIdx), CourierTier.FAVORITE, at = now + n))
                n++
            }
        }
        assertEquals(CourierDao.Limits.MAX_ENVELOPES.toLong(), dao.count(now + n))
        // A verified deposit cannot displace favorites when the store holds only favorites.
        assertFalse(deposit(n, key(200), CourierTier.VERIFIED, at = now + n))
        // A favorite deposit evicts the oldest (favorite) to make room.
        assertTrue(deposit(n + 1, key(201), CourierTier.FAVORITE, at = now + n + 1))
        assertEquals(CourierDao.Limits.MAX_ENVELOPES.toLong(), dao.count(now + n + 2))
    }

    @Test
    fun wipe_drops_all_carried_mail() = runTest {
        assertTrue(deposit(0, key(1), CourierTier.FAVORITE))
        dao.wipe()
        assertEquals(0L, dao.count(now))
    }
}
